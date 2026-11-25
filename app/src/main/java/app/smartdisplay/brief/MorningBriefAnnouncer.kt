package app.smartdisplay.brief

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.calendar.CalendarAnnounceKind
import app.smartdisplay.calendar.CalendarAnnouncer
import app.smartdisplay.expiration.ExpirationAnnouncer
import app.smartdisplay.garbage.GarbageAnnouncer
import app.smartdisplay.notification.NotificationCategoryTransit
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.transit.LineOperation
import app.smartdisplay.transit.OperationKind
import app.smartdisplay.transit.TrainLine
import app.smartdisplay.transit.TransitMonitor
import app.smartdisplay.transit.toStatusMark
import app.smartdisplay.util.launchRetryLoop
import app.smartdisplay.weather.WeatherAnnounceKind
import app.smartdisplay.weather.WeatherAnnouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

private data class MorningBriefSlot(
    val hour: Int,
    val minute: Int,
)

fun isMorningBriefSlot(time: LocalDateTime): Boolean {
    return MorningBriefSlots.any { it.hour == time.hour && it.minute == time.minute }
}

private val MorningBriefSlots = listOf(
    MorningBriefSlot(6, 0),
    MorningBriefSlot(7, 0),
    MorningBriefSlot(8, 0),
    MorningBriefSlot(8, 30),
    MorningBriefSlot(9, 0),
)

private val PostedAtInSpeech = Regex(
    """[（(][\s\u3000]*\d{1,2}月\d{1,2}日[\s\u3000]*\d{1,2}時\d{1,2}分掲載[\s\u3000]*[）)]""",
)

/**
 * 6:00・7:00・8:00・8:30・9:00 に今日の天気、予定、運行、ごみ、賞味期限を続ける。
 * いずれも温湿度も読む。時報と重なったら時報のあとへ回す。
 * 外部の取得は、時報が鳴り始めたときに始める。
 */
class MorningBriefAnnouncer(
    private val notifications: NotificationCenter,
    private val weatherAnnouncer: WeatherAnnouncer,
    private val calendarAnnouncer: CalendarAnnouncer,
    private val transitMonitor: TransitMonitor,
    private val garbageAnnouncer: GarbageAnnouncer,
    private val expirationAnnouncer: ExpirationAnnouncer,
    private val announcementGate: AnnouncementGate,
    private val chimeEnabled: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deliverMutex = Mutex()
    private var loopJob: Job? = null
    private var testJob: Job? = null
    private val announced = mutableSetOf<String>()

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launchRetryLoop(PollIntervalMs) { awaitAndAnnounce() }
    }

    fun playTest() {
        if (testJob?.isActive == true) {
            return
        }
        testJob = scope.launch {
            deliver(LocalDate.now())
        }
    }

    fun release() {
        loopJob?.cancel()
        loopJob = null
        testJob?.cancel()
        testJob = null
        scope.cancel()
    }

    private suspend fun awaitAndAnnounce() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        for (slot in MorningBriefSlots) {
            val key = "$today-${slot.hour}-${slot.minute}"
            if (key in announced) {
                continue
            }
            val target = today.atTime(slot.hour, slot.minute)
            val untilMs = ChronoUnit.MILLIS.between(now, target)
            if (untilMs > 0) {
                delay(untilMs.coerceAtMost(PollIntervalMs))
                return
            }
            val lateSec = ChronoUnit.SECONDS.between(target, LocalDateTime.now())
            if (lateSec > GraceSeconds) {
                announced += key
                continue
            }
            announced += key
            deliver(today)
            return
        }
        delay(PollIntervalMs)
    }

    private suspend fun deliver(day: LocalDate) {
        if (chimeEnabled()) {
            announcementGate.awaitChimeIfStarting()
        }
        deliverMutex.withLock {
            weatherAnnouncer.postAnnouncement(
                WeatherAnnounceKind.Today,
                includeIndoorOutdoor = true,
            )
            calendarAnnouncer.postAnnouncement(CalendarAnnounceKind.Today)
            postTransit()
            garbageAnnouncer.post(day, tomorrow = false)
            expirationAnnouncer.postCloser(day)
        }
    }

    private suspend fun postTransit() {
        val operations = transitMonitor.snapshotForAnnouncement()
        val known = TrainLine.entries.mapNotNull { operations[it] }
        if (known.isEmpty()) {
            postTransitSummary("運行情報を取得できませんでした。")
            return
        }
        val abnormal = known.filter { it.kind != OperationKind.Normal }
        if (abnormal.isNotEmpty()) {
            abnormal.forEach { operation ->
                notifications.post(
                    category = NotificationCategoryTransit,
                    title = operation.line.displayName,
                    body = "${operation.statusLabel}\n${operation.detail}",
                    speechText = abnormalSpeech(operation),
                    statusMark = operation.kind.toStatusMark(),
                    iconResIds = listOf(operation.line.iconResId),
                    alert = true,
                    addToHistory = false,
                    soundEffect = NotificationSoundEffect.None,
                    priority = true,
                )
            }
            return
        }
        if (known.size == TrainLine.entries.size) {
            val names = TrainLine.entries.joinToString("と") { it.displayName }
            val spokenNames = TrainLine.entries.joinToString("と、") { it.displayName }
            postTransitSummary(
                body = "${names}は平常運転です。",
                speechText = "${spokenNames}は、平常運転です。",
            )
            return
        }
        postTransitSummary(
            known.joinToString("") { "${it.line.displayName}は平常運転です。" },
        )
    }

    private fun postTransitSummary(body: String, speechText: String = body) {
        notifications.post(
            category = NotificationCategoryTransit,
            title = "運行情報",
            body = body,
            speechText = speechText,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private companion object {
        const val PollIntervalMs = 30_000L
        const val GraceSeconds = 90L
    }
}

private fun abnormalSpeech(operation: LineOperation): String {
    val detail = operation.detail
        .replace(PostedAtInSpeech, "")
        .trim()
        .trimEnd('。')
        .trim()
    val phrase = detail.ifBlank {
        operation.statusLabel.trim().trimEnd('。')
    }.ifBlank {
        "異常があります"
    }
    return "${operation.line.displayName}は、$phrase。"
}

@Composable
fun rememberMorningBriefAnnouncer(
    notifications: NotificationCenter,
    weatherAnnouncer: WeatherAnnouncer,
    calendarAnnouncer: CalendarAnnouncer,
    transitMonitor: TransitMonitor,
    garbageAnnouncer: GarbageAnnouncer,
    expirationAnnouncer: ExpirationAnnouncer,
    announcementGate: AnnouncementGate,
    chimeEnabled: () -> Boolean,
): MorningBriefAnnouncer {
    val announcer = remember(
        notifications,
        weatherAnnouncer,
        calendarAnnouncer,
        transitMonitor,
        garbageAnnouncer,
        expirationAnnouncer,
        announcementGate,
    ) {
        MorningBriefAnnouncer(
            notifications = notifications,
            weatherAnnouncer = weatherAnnouncer,
            calendarAnnouncer = calendarAnnouncer,
            transitMonitor = transitMonitor,
            garbageAnnouncer = garbageAnnouncer,
            expirationAnnouncer = expirationAnnouncer,
            announcementGate = announcementGate,
            chimeEnabled = chimeEnabled,
        )
    }
    DisposableEffect(announcer) {
        announcer.start()
        onDispose { announcer.release() }
    }
    return announcer
}
