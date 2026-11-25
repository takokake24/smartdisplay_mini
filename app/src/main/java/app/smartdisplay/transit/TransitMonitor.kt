package app.smartdisplay.transit

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryTransit
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDateTime

/** 平常運転以外（遅延・見合わせなど）のときの取得間隔 */
private const val PollIntervalAlertMillis = 90_000L
/** 平常運転のときの取得間隔 */
private const val PollIntervalNormalMillis = 5L * 60_000L
/** 詳細末尾の掲載時刻。表示には残し、読み上げからは外す */
private val PostedAtInSpeech = Regex(
    """[（(][\s\u3000]*\d{1,2}月\d{1,2}日[\s\u3000]*\d{1,2}時\d{1,2}分掲載[\s\u3000]*[）)]""",
)
/** ミュート中の待機（テーマ日切替の検知は ClockScreen 側でも行う） */
private const val PollIdleWhileMutedMillis = 60_000L
/** 自動取得を止める時間帯: 1:00〜4:00未満 */
private const val NightQuietStartHour = 1
private const val NightQuietEndHour = 4
private const val Tag = "TransitMonitor"

class TransitMonitor(
    private val notifications: NotificationCenter,
    private val client: YahooTransitClient = YahooTransitClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var announcementRefresh: Deferred<Map<TrainLine, LineOperation>>? = null
    private val previousKinds = mutableMapOf<TrainLine, OperationKind>()
    private val nextDueAtMillis = mutableMapOf<TrainLine, Long>()

    @Volatile
    private var pollingSuppressed = false

    private val _operations = MutableStateFlow<Map<TrainLine, LineOperation>>(emptyMap())
    val operations: StateFlow<Map<TrainLine, LineOperation>> = _operations.asStateFlow()

    private val _lastFetchedAt = MutableStateFlow<LocalDateTime?>(null)
    val lastFetchedAt: StateFlow<LocalDateTime?> = _lastFetchedAt.asStateFlow()

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        restartPollingLoop()
    }

    /**
     * 「今日は通知しない」中は定期の外部取得を止める。
     * メニューからの手動更新 [requestRefresh] は抑止しない。
     */
    fun setPollingSuppressed(suppressed: Boolean) {
        if (pollingSuppressed == suppressed && loopJob?.isActive == true) {
            return
        }
        pollingSuppressed = suppressed
        restartPollingLoop()
    }

    private fun restartPollingLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                if (pollingSuppressed) {
                    delay(PollIdleWhileMutedMillis)
                    continue
                }
                val now = LocalDateTime.now()
                if (isNightQuietHours(now)) {
                    delay(millisUntilNightQuietEnd(now))
                    continue
                }
                val due = linesDue()
                if (due.isNotEmpty()) {
                    refreshLines(due)
                }
                if (pollingSuppressed || isNightQuietHours(LocalDateTime.now())) {
                    continue
                }
                delay(millisUntilNextDue())
            }
        }
    }

    /** 1時〜4時の間は自動取得しない（メニューからの手動更新は可）。 */
    private fun isNightQuietHours(now: LocalDateTime): Boolean {
        return now.hour in NightQuietStartHour until NightQuietEndHour
    }

    private fun millisUntilNightQuietEnd(now: LocalDateTime): Long {
        val end = now.toLocalDate().atTime(NightQuietEndHour, 0)
        return Duration.between(now, end).toMillis().coerceAtLeast(1L)
    }

    /** 期限を過ぎた路線だけ返す。未取得の路線はすぐ対象にする。 */
    private fun linesDue(nowMillis: Long = System.currentTimeMillis()): List<TrainLine> {
        return TrainLine.entries.filter { line ->
            nowMillis >= (nextDueAtMillis[line] ?: 0L)
        }
    }

    private fun millisUntilNextDue(nowMillis: Long = System.currentTimeMillis()): Long {
        val soonest = TrainLine.entries.minOf { line ->
            nextDueAtMillis[line] ?: 0L
        }
        return (soonest - nowMillis).coerceAtLeast(1L)
    }

    /** 平常運転は5分、それ以外と未取得は1分30秒。路線ごとに次の取得時刻を決める。 */
    private fun scheduleNext(
        lines: List<TrainLine>,
        fetched: Map<TrainLine, LineOperation> = emptyMap(),
    ) {
        val now = System.currentTimeMillis()
        lines.forEach { line ->
            val kind = fetched[line]?.kind ?: _operations.value[line]?.kind
            val interval = if (kind == OperationKind.Normal) {
                PollIntervalNormalMillis
            } else {
                PollIntervalAlertMillis
            }
            nextDueAtMillis[line] = now + interval
        }
    }

    /** 朝のまとめ用。未取得の路線があれば、時報の鳴り始めに取り始める。 */
    fun prepareForAnnouncement() {
        if (TrainLine.entries.all { it in _operations.value }) {
            return
        }
        if (announcementRefresh?.isActive == true) {
            return
        }
        announcementRefresh = scope.async {
            withTimeoutOrNull(10_000L) {
                refreshLines(TrainLine.entries)
            }
            _operations.value
        }
    }

    /** 朝のまとめ用。未取得の路線があれば短く待って取り直す。 */
    suspend fun snapshotForAnnouncement(): Map<TrainLine, LineOperation> {
        val pending = announcementRefresh
        if (pending != null) {
            announcementRefresh = null
            return pending.await()
        }
        if (TrainLine.entries.all { it in _operations.value }) {
            return _operations.value
        }
        withTimeoutOrNull(10_000L) {
            refreshLines(TrainLine.entries)
        }
        return _operations.value
    }

    fun requestRefresh(onFetchFailures: ((List<String>) -> Unit)? = null) {
        scope.launch {
            val failedLabels = refreshLines(TrainLine.entries, repeatAlert = true)
            if (failedLabels.isNotEmpty()) {
                onFetchFailures?.invoke(failedLabels)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    /**
     * @return 取得に失敗した路線の表示名
     */
    private suspend fun refreshLines(
        lines: List<TrainLine>,
        repeatAlert: Boolean = false,
    ): List<String> {
        if (lines.isEmpty()) {
            return emptyList()
        }
        if (!InternetConnectivity.hasInternet()) {
            scheduleNext(lines)
            return emptyList()
        }
        val next = linkedMapOf<TrainLine, LineOperation>()
        val failedLabels = mutableListOf<String>()
        lines.forEach { line ->
            runCatching { client.fetch(line) }
                .onSuccess { operation ->
                    next[line] = operation
                    handleTransition(operation)
                }
                .onFailure { error ->
                    if (error is CancellationException) {
                        throw error
                    }
                    if (error is NoInternetException) {
                        return@onFailure
                    }
                    Log.w(Tag, "failed to fetch ${line.displayName}", error)
                    failedLabels += line.displayName
                }
        }
        if (next.isNotEmpty()) {
            _operations.value = _operations.value + next
            _lastFetchedAt.value = LocalDateTime.now()
        }
        scheduleNext(lines, fetched = next)
        if (!InternetConnectivity.hasInternet()) {
            return emptyList()
        }
        FetchFailureNotifier.clear(next.keys.map { it.displayName })
        if (repeatAlert) {
            FetchFailureNotifier.clear(failedLabels)
        }
        FetchFailureNotifier.report(failedLabels)
        return failedLabels
    }

    private fun handleTransition(operation: LineOperation) {
        val previous = previousKinds[operation.line]
        previousKinds[operation.line] = operation.kind
        if (!shouldAnnounceTransition(previous, operation.kind)) {
            return
        }
        val speech = buildSpeech(operation)
        notifications.post(
            category = NotificationCategoryTransit,
            title = operation.line.displayName,
            body = "${operation.statusLabel}\n${operation.detail}",
            speechText = speech,
            statusMark = operation.kind.toStatusMark(),
            iconResIds = listOf(operation.line.iconResId),
            alert = true,
        )
    }

    private fun buildSpeech(operation: LineOperation): String {
        val detail = operation.detail
            .replace(PostedAtInSpeech, "")
            .trim()
            .trimEnd('。')
            .trim() + "。"
        return "鉄道運行情報のお知らせです。${operation.line.displayName}は、$detail"
    }
}

@Composable
fun rememberTransitMonitor(notifications: NotificationCenter): TransitMonitor {
    val monitor = remember(notifications) { TransitMonitor(notifications) }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.release() }
    }
    return monitor
}
