package app.smartdisplay.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.notification.NotificationCategoryCalendarSoon
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

/** 開始15分前の予定を、1回だけ通知する。 */
class CalendarSoonMonitor(
    context: Context,
    private val settings: CalendarSettings,
    private val notifications: NotificationCenter,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val repository = CalendarRepository(context.applicationContext, zoneId)
    private val store = CalendarSoonStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launch {
            while (isActive) {
                try {
                    checkOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
                delay(PollIntervalMs)
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

    fun postTest(now: LocalDateTime = LocalDateTime.now(zoneId)) {
        post(calendarSoonTestAnnouncement(now))
    }

    suspend fun checkOnce(now: LocalDateTime = LocalDateTime.now(zoneId)) {
        if (!repository.hasPermission()) {
            return
        }
        val calendars = withContext(Dispatchers.IO) { repository.loadCalendars() }
        val enabledIds = settings.enabledIdsAmong(calendars)
        if (enabledIds.isEmpty()) {
            return
        }
        val rangeEnd = now.plusMinutes(CalendarSoonLeadMinutes.toLong()).plusSeconds(1)
        val events = withContext(Dispatchers.IO) {
            repository.loadSoonEvents(enabledIds, now, rangeEnd)
        }
        val due = dueCalendarSoonAnnouncements(
            events = events,
            now = now,
            announcedKeys = store.announcedKeys(now),
        )
        if (due.isEmpty() || notifications.isMutedToday(NotificationCategoryCalendarSoon, now)) {
            return
        }
        due.forEach { announcement ->
            post(announcement)
            store.markAnnounced(announcement.keys, now)
        }
    }

    private fun post(announcement: CalendarSoonAnnouncement) {
        notifications.post(
            category = NotificationCategoryCalendarSoon,
            title = announcement.title,
            body = announcement.body,
            speechText = announcement.speechText,
            calendarItems = announcement.items,
            eventCount = announcement.eventCount,
            alert = true,
            addToHistory = true,
            soundEffect = NotificationSoundEffect.Notice,
            priority = true,
        )
    }

    private companion object {
        const val PollIntervalMs = 20_000L
    }
}

/** 通知済みの予定。開始を過ぎた記録は捨てる。 */
class CalendarSoonStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun announcedKeys(now: LocalDateTime): Set<String> = synchronized(this) {
        val raw = prefs.getStringSet(KeyAnnounced, emptySet()).orEmpty().toSet()
        val kept = raw.filter { isStillUpcoming(it, now) }.toSet()
        if (kept.size != raw.size) {
            prefs.edit().putStringSet(KeyAnnounced, kept).apply()
        }
        kept
    }

    fun markAnnounced(keys: Collection<String>, now: LocalDateTime) = synchronized(this) {
        if (keys.isEmpty()) {
            return
        }
        val kept = prefs.getStringSet(KeyAnnounced, emptySet()).orEmpty()
            .filter { isStillUpcoming(it, now) }
            .toMutableSet()
        kept += keys
        prefs.edit().putStringSet(KeyAnnounced, kept).apply()
    }

    private fun isStillUpcoming(key: String, now: LocalDateTime): Boolean {
        val start = key.substringAfter('|', "")
        val time = runCatching { LocalDateTime.parse(start) }.getOrNull() ?: return false
        return time.isAfter(now)
    }

    private companion object {
        const val PrefsName = "calendar_soon"
        const val KeyAnnounced = "announced_keys"
    }
}

@Composable
fun rememberCalendarSoonMonitor(
    settings: CalendarSettings,
    notifications: NotificationCenter,
): CalendarSoonMonitor {
    val context = LocalContext.current
    val monitor = remember(settings, notifications) {
        CalendarSoonMonitor(
            context = context.applicationContext,
            settings = settings,
            notifications = notifications,
        )
    }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.release() }
    }
    return monitor
}
