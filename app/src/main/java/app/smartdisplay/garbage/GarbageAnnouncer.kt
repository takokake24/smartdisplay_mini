package app.smartdisplay.garbage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryGarbage
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.util.launchRetryLoop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 23:30 の明日の予定のあとに、明日のごみを読む。
 * そのあと [afterTomorrow] で賞味期限を続ける。
 * 時報と重なったら時報のあとへ回す。
 */
class GarbageAnnouncer(
    private val notifications: NotificationCenter,
    private val announcementGate: AnnouncementGate,
    private val chimeEnabled: () -> Boolean,
    private val afterTomorrow: suspend () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var announcedDate: LocalDate? = null

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launchRetryLoop(PollIntervalMs) { awaitAndAnnounce() }
    }

    fun post(date: LocalDate, tomorrow: Boolean) {
        val kinds = garbageKindsOn(date)
        if (kinds.isEmpty()) {
            return
        }
        val speech = garbageDaySpeech(kinds, tomorrow = tomorrow)
        notifications.post(
            category = NotificationCategoryGarbage,
            title = "ごみ出し",
            body = speech,
            speechText = speech,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    fun release() {
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }

    private suspend fun awaitAndAnnounce() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        if (announcedDate == today) {
            delay(PollIntervalMs)
            return
        }
        val target = today.atTime(23, 30)
        val untilMs = ChronoUnit.MILLIS.between(now, target)
        if (untilMs > 0) {
            delay(untilMs.coerceAtMost(PollIntervalMs))
            return
        }
        val lateSec = ChronoUnit.SECONDS.between(target, LocalDateTime.now())
        if (lateSec > GraceSeconds) {
            announcedDate = today
            delay(PollIntervalMs)
            return
        }
        announcedDate = today
        if (chimeEnabled()) {
            announcementGate.awaitChimeIfStarting()
        }
        post(today.plusDays(1), tomorrow = true)
        afterTomorrow()
    }

    private companion object {
        const val PollIntervalMs = 30_000L
        const val GraceSeconds = 90L
    }
}

@Composable
fun rememberGarbageAnnouncer(
    notifications: NotificationCenter,
    announcementGate: AnnouncementGate,
    chimeEnabled: () -> Boolean,
    afterTomorrow: suspend () -> Unit = {},
): GarbageAnnouncer {
    val announcer = remember(notifications, announcementGate) {
        GarbageAnnouncer(
            notifications = notifications,
            announcementGate = announcementGate,
            chimeEnabled = chimeEnabled,
            afterTomorrow = afterTomorrow,
        )
    }
    DisposableEffect(announcer) {
        announcer.start()
        onDispose { announcer.release() }
    }
    return announcer
}
