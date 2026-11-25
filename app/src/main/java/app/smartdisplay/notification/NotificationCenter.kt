package app.smartdisplay.notification

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.audio.EffectPlayer
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import app.smartdisplay.speech.breathAtHalfWidthSpace
import app.smartdisplay.weather.WeatherSnapshot
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.math.max

data class ActiveNotificationPresentation(
    val notification: AppNotification,
    val queueProgress: NotificationQueueProgress?,
)

/**
 * 画面右半分の通知バナーと履歴をまとめて扱う。
 * 音声案内は [AnnouncementGate] 経由で、時報など他の案内と順番に出す。
 * 複数同時に来た場合は読み上げ完了ごとに次へ遷移し、前のものは未確認のまま履歴に残す。
 * 天気・予定など [AppNotification.priority] の通知は、溜まっている通常通知より先に割り込む。
 */
class NotificationCenter(
    context: Context,
    private val speaker: SpeechSpeaker,
    private val effects: EffectPlayer,
    private val announcementGate: AnnouncementGate,
) {
    private val appContext = context.applicationContext
    private val muteStore = NotificationMuteStore(appContext)
    private val retainedStore = RetainedNotificationStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingAlerts = ArrayDeque<AppNotification>()
    private val deferredAlerts = ArrayDeque<AppNotification>()
    private var autoDismissJob: Job? = null
    private var presentingJob: Job? = null
    private var batchPresented = 0
    private var batchTotal = 0
    @Volatile
    private var crisisExclusive = false

    @Volatile
    private var awayAlertExclusive = false

    @Volatile
    private var soundTestHold = false

    /** 緊急全画面か外出アラートのあいだ。普通の通知は保留する。 */
    val isEarthquakeExclusive: Boolean
        get() = crisisExclusive || awayAlertExclusive

    /** 緊急地震速報・気象特別警報の排他。手動で閉じた判定はこちらを見る。 */
    fun isCrisisExclusiveHold(): Boolean = crisisExclusive

    private var userDismissListener: ((String) -> Unit)? = null

    /** ユーザーが通知を消したとき。割り込みで一旦引っ込めただけでは呼ばない。 */
    fun setUserDismissListener(listener: ((String) -> Unit)?) {
        userDismissListener = listener
    }

    private fun notifyUserDismissed(id: String) {
        userDismissListener?.invoke(id)
    }

    private val _presentation = MutableStateFlow<ActiveNotificationPresentation?>(null)
    val presentation: StateFlow<ActiveNotificationPresentation?> = _presentation.asStateFlow()

    private val _history = MutableStateFlow<List<AppNotification>>(emptyList())
    val history: StateFlow<List<AppNotification>> = _history.asStateFlow()

    private var pruneJob: Job? = null

    val unreadCount: Int
        get() = _history.value.count { !it.read }

    init {
        val kept = retainedStore.load()
        if (kept.isNotEmpty()) {
            _history.value = kept
        }
        pruneJob = scope.launch {
            while (isActive) {
                pruneExpiredHistory()
                delay(NotificationHistoryPruneIntervalMillis)
            }
        }
    }

    fun post(
        category: String,
        title: String,
        body: String,
        speechText: String,
        statusMark: NotificationStatusMark? = null,
        iconResIds: List<Int> = emptyList(),
        calendarItems: List<CalendarNotificationItem> = emptyList(),
        expirationItems: List<ExpirationNotificationItem> = emptyList(),
        memorialNames: List<String> = emptyList(),
        quoteAuthor: String = "",
        eventCount: Int? = null,
        weatherSnapshot: WeatherSnapshot? = null,
        stepsToday: Long? = null,
        stepsWeek: Long? = null,
        earthquakeIntensityScale: Int? = null,
        earthquakeOccurredAt: LocalDateTime? = null,
        alert: Boolean = true,
        addToHistory: Boolean = true,
        soundEffect: NotificationSoundEffect = NotificationSoundEffect.Notice,
        priority: Boolean = false,
        allowDuringEarthquakeExclusive: Boolean = false,
        skipSpeech: Boolean = false,
        appIcon: Bitmap? = null,
        sourceLabel: String = "",
        speakNotBeforeElapsed: Long = 0L,
        retained: Boolean = false,
        holdUntilDismiss: Boolean = false,
    ): String {
        val item = AppNotification(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            body = body,
            speechText = speechText,
            createdAt = LocalDateTime.now(),
            statusMark = statusMark,
            iconResIds = iconResIds,
            calendarItems = calendarItems,
            expirationItems = expirationItems,
            memorialNames = memorialNames,
            quoteAuthor = quoteAuthor,
            eventCount = eventCount,
            weatherSnapshot = weatherSnapshot,
            stepsToday = stepsToday,
            stepsWeek = stepsWeek,
            earthquakeIntensityScale = earthquakeIntensityScale,
            earthquakeOccurredAt = earthquakeOccurredAt,
            soundEffect = soundEffect,
            priority = priority,
            skipSpeech = skipSpeech,
            appIcon = appIcon,
            sourceLabel = sourceLabel,
            speakNotBeforeElapsed = speakNotBeforeElapsed,
            read = false,
            retained = retained,
            holdUntilDismiss = holdUntilDismiss,
        )
        pruneExpiredHistory()
        if (addToHistory) {
            _history.update { listOf(item) + it }
            if (item.retained) {
                persistRetained()
            }
        }
        val muted = supportsMuteToday(category) && muteStore.isMutedToday(category)
        if (!alert || muted) {
            return item.id
        }
        if (isEarthquakeExclusive && !allowDuringEarthquakeExclusive) {
            deferredAlerts.addLast(item)
            return item.id
        }
        if (soundTestHold && !item.isEmergencyNotification) {
            deferredAlerts.addLast(item)
            return item.id
        }
        if (priority) {
            enqueuePriority(item)
            if (interruptActiveForIncoming(item)) {
                return item.id
            }
        } else {
            pendingAlerts.addLast(item)
        }
        expandBatchTotalForPending()
        refreshPresentationProgress()
        ensurePresenting()
        return item.id
    }

    /** 読み上げ失敗。beep のあとに [message] を読み、右バナーに出す。 */
    fun postReadoutFailure(title: String, message: String) {
        post(
            category = NotificationCategoryGeneral,
            title = title,
            body = message,
            speechText = message,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.Beep,
            priority = true,
        )
    }

    /** 緊急地震速報の割り込み。進行中・待ち通知は破棄（履歴済みはそのまま）。 */
    fun abortForEarthquakePriority() {
        pendingAlerts.clear()
        stopAlertPlayback()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        resetQueueProgress()
    }

    fun setEarthquakeExclusive(active: Boolean) {
        crisisExclusive = active
        if (isEarthquakeExclusive) {
            return
        }
        flushDeferredAlerts()
    }

    /** 外出アラートのあいだ、時報以外の通知も受け付けない。 */
    fun setAwayAlertExclusive(active: Boolean) {
        awayAlertExclusive = active
        if (isEarthquakeExclusive) {
            return
        }
        flushDeferredAlerts()
    }

    /**
     * サウンドテスト中は通常の通知を保留する。
     * 緊急地震速報・震度・気象警報はそのまま出す。抜けたら保留分を表示する。
     */
    fun setSoundTestHold(active: Boolean) {
        val was = soundTestHold
        soundTestHold = active
        if (active) {
            shelveNonEmergencyForSoundTest()
            return
        }
        if (was && !isEarthquakeExclusive) {
            flushDeferredAlerts()
        }
    }

    private fun shelveNonEmergencyForSoundTest() {
        val active = _presentation.value?.notification
        if (active != null && !active.isEmergencyNotification) {
            deferredAlerts.addFirst(active)
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            resetQueueProgress()
        }
        val emergencies = ArrayDeque<AppNotification>()
        while (pendingAlerts.isNotEmpty()) {
            val item = pendingAlerts.removeFirst()
            if (item.isEmergencyNotification) {
                emergencies.addLast(item)
            } else {
                deferredAlerts.addLast(item)
            }
        }
        pendingAlerts.addAll(emergencies)
        if (pendingAlerts.isNotEmpty()) {
            ensurePresenting()
        }
    }

    private fun flushDeferredAlerts() {
        while (deferredAlerts.isNotEmpty()) {
            val item = deferredAlerts.removeFirst()
            if (item.priority) {
                enqueuePriority(item)
            } else {
                pendingAlerts.addLast(item)
            }
        }
        expandBatchTotalForPending()
        refreshPresentationProgress()
        ensurePresenting()
    }

    /** 緊急排他バナーを履歴へ移す。震度など別通知の表示中ならそちらは残す。 */
    fun finishEarthquakeExclusiveBanner() {
        val active = _presentation.value?.notification
        val pendingCrisis = pendingAlerts.filter { it.isCrisisExclusiveBanner }
        pendingAlerts.removeAll { it.isCrisisExclusiveBanner }
        val toHistory = buildList {
            if (active?.isCrisisExclusiveBanner == true) {
                add(active)
            }
            addAll(pendingCrisis)
        }.distinctBy { it.id }
        if (active?.isCrisisExclusiveBanner == true) {
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            resetQueueProgress()
        }
        if (toHistory.isNotEmpty()) {
            _history.update { list ->
                val existing = list.map { it.id }.toSet()
                val fresh = toHistory.filterNot { it.id in existing }.map { it.copy(read = false) }
                fresh + list
            }
        }
    }

    fun dismissActiveManually() {
        val active = _presentation.value?.notification
        val endCrisisExclusive = isCrisisExclusiveHold() && active?.isCrisisExclusiveBanner == true
        val activeId = active?.id
        stopAlertPlayback()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        if (activeId != null) {
            _history.update { list -> list.filterNot { it.id == activeId } }
            notifyUserDismissed(activeId)
        }
        if (endCrisisExclusive) {
            // 手動クローズ時点で排他を解除し、保留通知をすぐ出す
            setEarthquakeExclusive(false)
        } else {
            ensurePresenting()
        }
    }

    fun dismissActive() {
        stopAlertPlayback()
        pendingAlerts.clear()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        resetQueueProgress()
    }

    fun isMutedToday(category: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        return supportsMuteToday(category) && muteStore.isMutedToday(category, now)
    }

    fun muteTodayAndDismiss(category: String) {
        if (!supportsMuteToday(category)) {
            return
        }
        muteStore.muteToday(category)
        pendingAlerts.removeAll { it.category == category }
        if (_presentation.value?.notification?.category == category) {
            val activeId = _presentation.value?.notification?.id
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            if (activeId != null) {
                _history.update { list -> list.filterNot { it.id == activeId } }
            }
        }
        expandBatchTotalForPending()
        refreshPresentationProgress()
        ensurePresenting()
    }

    fun isMutedToday(category: String): Boolean = muteStore.isMutedToday(category)

    fun markRead(id: String) {
        _history.update { list ->
            list.map { item ->
                if (item.id == id) item.copy(read = true) else item
            }
        }
    }

    fun remove(id: String) {
        pendingAlerts.removeAll { it.id == id }
        deferredAlerts.removeAll { it.id == id }
        _history.update { list -> list.filterNot { it.id == id } }
        persistRetained()
        if (_presentation.value?.notification?.id == id) {
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            ensurePresenting()
        } else {
            expandBatchTotalForPending()
            refreshPresentationProgress()
        }
        notifyUserDismissed(id)
    }

    fun clearHistory() {
        val ids = buildList {
            _history.value.forEach { add(it.id) }
            pendingAlerts.forEach { add(it.id) }
            deferredAlerts.forEach { add(it.id) }
            _presentation.value?.notification?.id?.let { add(it) }
        }.distinct()
        pendingAlerts.clear()
        deferredAlerts.clear()
        _history.value = emptyList()
        persistRetained()
        dismissActive()
        ids.forEach(::notifyUserDismissed)
    }

    fun clearUnread() {
        _history.update { list -> list.map { it.copy(read = true) } }
    }

    fun release() {
        stopAlertPlayback()
        pendingAlerts.clear()
        autoDismissJob?.cancel()
        pruneJob?.cancel()
        pruneJob = null
        scope.cancel()
    }

    private fun enqueuePriority(item: AppNotification) {
        val rebuilt = ArrayDeque<AppNotification>()
        var inserted = false
        for (existing in pendingAlerts) {
            if (!inserted && !existing.priority) {
                rebuilt.addLast(item)
                inserted = true
            }
            rebuilt.addLast(existing)
        }
        if (!inserted) {
            rebuilt.addLast(item)
        }
        pendingAlerts.clear()
        pendingAlerts.addAll(rebuilt)
    }

    private fun enqueueNonPriorityFront(item: AppNotification) {
        val rebuilt = ArrayDeque<AppNotification>()
        var inserted = false
        for (existing in pendingAlerts) {
            if (!inserted && !existing.priority) {
                rebuilt.addLast(item)
                inserted = true
            }
            rebuilt.addLast(existing)
        }
        if (!inserted) {
            rebuilt.addLast(item)
        }
        pendingAlerts.clear()
        pendingAlerts.addAll(rebuilt)
    }

    /**
     * 優先通知（や震度情報）が来たら、必要に応じて進行中を切り上げる。
     * @return 割り込みで再提示を始めたら true
     */
    private fun interruptActiveForIncoming(incoming: AppNotification): Boolean {
        val active = _presentation.value?.notification ?: return false
        val shouldInterrupt = when {
            incoming.isEarthquakeIntensity && !active.isEarthquakeIntensity -> true
            incoming.priority && !active.priority -> true
            else -> false
        }
        if (!shouldInterrupt) {
            expandBatchTotalForPending()
            refreshPresentationProgress()
            return false
        }
        stopAlertPlayback()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        if (active.priority) {
            enqueuePriority(active)
        } else {
            enqueueNonPriorityFront(active)
        }
        expandBatchTotalForPending()
        refreshPresentationProgress()
        ensurePresenting()
        return true
    }

    private fun pruneExpiredHistory() {
        val cutoff = LocalDateTime.now().minusHours(NotificationHistoryRetentionHours)
        val expiredIds = _history.value
            .asSequence()
            .filter { !it.retained && !it.holdUntilDismiss && it.createdAt.isBefore(cutoff) }
            .map { it.id }
            .toSet()
        if (expiredIds.isEmpty()) {
            return
        }
        expiredIds.forEach(::notifyUserDismissed)
        pendingAlerts.removeAll { it.id in expiredIds }
        deferredAlerts.removeAll { it.id in expiredIds }
        _history.update { list -> list.filterNot { it.id in expiredIds } }
        val activeId = _presentation.value?.notification?.id
        if (activeId != null && activeId in expiredIds) {
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            ensurePresenting()
        } else {
            expandBatchTotalForPending()
            refreshPresentationProgress()
        }
    }

    private fun ensurePresenting() {
        if (presentingJob?.isActive == true) {
            return
        }
        if (pendingAlerts.isEmpty()) {
            return
        }
        if (_presentation.value == null) {
            batchPresented = 0
            batchTotal = pendingAlerts.size
        }
        presentingJob = scope.launch {
            try {
                while (true) {
                    val current = pendingAlerts.removeFirstOrNull() ?: break
                    batchPresented += 1
                    batchTotal = max(batchTotal, batchPresented + pendingAlerts.size)
                    showBanner(current)
                    if (current.holdUntilDismiss) {
                        presentation.first { it?.notification?.id != current.id }
                        continue
                    }
                    // 時報鳴動中に来た通知は表示だけ先に出し、読み上げは時報一連の後
                    announcementGate.awaitChimeSequenceIdle()
                    // 端末側の通知音が鳴り終わるまで読み上げを始めない
                    awaitExternalSound(current)
                    announcementGate.awaitChimeSequenceIdle()
                    // 表示の1分タイムアウトは、読み上げ処理の開始時点から数える
                    startAutoDismiss(current.id)
                    val speak = !current.skipSpeech && current.speechText.isNotBlank()
                    if (speak || current.soundEffect != NotificationSoundEffect.None) {
                        announcementGate.withLock {
                            if (!announcementGate.shouldContinueSpeech()) {
                                return@withLock
                            }
                            when (current.soundEffect) {
                                NotificationSoundEffect.None -> Unit
                                NotificationSoundEffect.Notice -> {
                                    runCatching { effects.playNotice() }
                                }
                                NotificationSoundEffect.Alert1 -> {
                                    runCatching { effects.playAlert1() }
                                }
                                NotificationSoundEffect.Beep -> {
                                    runCatching { effects.playBeepAndAwait() }
                                }
                                NotificationSoundEffect.Sunset -> {
                                    runCatching { effects.playSunset() }
                                }
                            }
                            if (!speak || !announcementGate.shouldContinueSpeech()) {
                                return@withLock
                            }
                            speaker.speakNow(spokenText(current))
                        }
                    }
                    // 読み上げ中に時報が始まった場合も、一連が終わるまで次へ進まない
                    announcementGate.awaitChimeSequenceIdle()
                }
            } catch (_: CancellationException) {
                // 手動閉じ・停止時
            } finally {
                if (presentingJob === this) {
                    presentingJob = null
                }
            }
        }
    }

    private suspend fun awaitExternalSound(item: AppNotification) {
        val remaining = item.speakNotBeforeElapsed - SystemClock.elapsedRealtime()
        if (remaining > 0L) {
            delay(remaining)
        }
    }

    private fun stopAlertPlayback() {
        presentingJob?.cancel()
        presentingJob = null
        speaker.stop()
    }

    private fun showBanner(item: AppNotification) {
        val progress = if (batchTotal > 1) {
            NotificationQueueProgress(current = batchPresented, total = batchTotal)
        } else {
            null
        }
        _presentation.value = ActiveNotificationPresentation(
            notification = item,
            queueProgress = progress,
        )
        // タイムアウトは読み上げ開始時に startAutoDismiss する
        autoDismissJob?.cancel()
        autoDismissJob = null
    }

    private fun startAutoDismiss(notificationId: String) {
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(ActiveNotificationTimeoutMillis)
            if (_presentation.value?.notification?.id == notificationId) {
                _presentation.value = null
                if (pendingAlerts.isEmpty()) {
                    resetQueueProgress()
                }
            }
        }
    }

    private fun expandBatchTotalForPending() {
        val shown = if (_presentation.value != null) batchPresented else 0
        batchTotal = max(batchTotal, shown + pendingAlerts.size)
    }

    private fun refreshPresentationProgress() {
        val current = _presentation.value ?: return
        val progress = if (batchTotal > 1) {
            NotificationQueueProgress(
                current = batchPresented.coerceAtLeast(1),
                total = batchTotal,
            )
        } else {
            null
        }
        if (current.queueProgress != progress) {
            _presentation.value = current.copy(queueProgress = progress)
        }
    }

    private fun spokenText(item: AppNotification): String {
        val text = item.speechText
        if (!pausesAtHalfWidthSpace(item.category)) {
            return text
        }
        return breathAtHalfWidthSpace(text)
    }

    /** 案内の読み上げ。半角スペースで一呼吸置く。 */
    private fun pausesAtHalfWidthSpace(category: String): Boolean {
        return category == NotificationCategoryWeather ||
            category == NotificationCategoryCalendar ||
            category == NotificationCategoryCalendarSoon ||
            category == NotificationCategoryMemorial ||
            category == NotificationCategoryQuote ||
            category == NotificationCategorySteps ||
            category == NotificationCategoryGarbage ||
            category == NotificationCategoryExpiration ||
            category == NotificationCategoryTransit ||
            category == NotificationCategorySunset ||
            category == NotificationCategoryDevice
    }

    private fun resetQueueProgress() {
        batchPresented = 0
        batchTotal = 0
    }

    private fun persistRetained() {
        retainedStore.save(_history.value.filter { it.retained })
    }
}

/** ユーザーが消すまで残す通知。アプリを開き直しても履歴に戻す。 */
private class RetainedNotificationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun load(): List<AppNotification> {
        val raw = prefs.getString(KeyItems, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val createdAt = runCatching {
                    LocalDateTime.parse(item.optString("createdAt"))
                }.getOrNull() ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                if (id.isEmpty() || title.isEmpty()) {
                    continue
                }
                add(
                    AppNotification(
                        id = id,
                        category = item.optString("category").ifBlank { NotificationCategoryAwayAlert },
                        title = title,
                        body = item.optString("body"),
                        speechText = "",
                        createdAt = createdAt,
                        soundEffect = NotificationSoundEffect.None,
                        skipSpeech = true,
                        retained = true,
                    ),
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun save(items: List<AppNotification>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("category", item.category)
                    .put("title", item.title)
                    .put("body", item.body)
                    .put("createdAt", item.createdAt.toString()),
            )
        }
        prefs.edit().putString(KeyItems, array.toString()).apply()
    }

    private companion object {
        const val PrefsName = "notification_history"
        const val KeyItems = "retained_items"
    }
}

@Composable
fun rememberNotificationCenter(
    speaker: SpeechSpeaker,
    effects: EffectPlayer,
    announcementGate: AnnouncementGate,
): NotificationCenter {
    val context = LocalContext.current
    val center = remember(speaker, effects, announcementGate) {
        NotificationCenter(
            context = context.applicationContext,
            speaker = speaker,
            effects = effects,
            announcementGate = announcementGate,
        )
    }
    DisposableEffect(center) {
        onDispose { center.release() }
    }
    return center
}

internal suspend fun awaitCrisisExclusiveHold(
    notifications: NotificationCenter,
    durationMs: Long,
) {
    val endAt = SystemClock.elapsedRealtime() + durationMs
    while (SystemClock.elapsedRealtime() < endAt) {
        if (!notifications.isCrisisExclusiveHold()) {
            return
        }
        delay(100L)
    }
}
