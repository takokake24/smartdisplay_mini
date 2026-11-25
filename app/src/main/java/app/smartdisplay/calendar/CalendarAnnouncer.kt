package app.smartdisplay.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.notification.NotificationCategoryCalendar
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.PreparedFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CalendarAnnouncer(
    context: Context,
    private val settings: CalendarSettings,
    private val notifications: NotificationCenter,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val repository = CalendarRepository(context.applicationContext, zoneId)
    private val prepared = PreparedFetch<CalendarPrepared>()

    suspend fun onChimeFinished(time: LocalDateTime) {
        val kind = calendarAnnounceKindForChime(time) ?: return
        postAnnouncement(kind, now = time)
    }

    /** 時報の鳴り始めに呼ぶ。案内の時点では、ここで始めた取得を使う。 */
    fun prepare(kind: CalendarAnnounceKind, now: LocalDateTime) {
        prepared.start(CalendarPrepareKey(kind, now.toLocalDate())) {
            loadPrepared(kind, now)
        }
    }

    suspend fun postAnnouncement(
        kind: CalendarAnnounceKind,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val key = CalendarPrepareKey(kind, now.toLocalDate())
        val loaded = prepared.take(key)?.takeUnless { it is CalendarPrepared.Offline }
            ?: loadPrepared(kind, now)
        val announcement = when (loaded) {
            CalendarPrepared.Offline,
            CalendarPrepared.NoCalendars,
            -> return
            CalendarPrepared.Permission -> {
                notifications.postReadoutFailure(
                    title = "予定",
                    message = "予定を取得するには、アプリの権限を変更してください。",
                )
                return
            }
            CalendarPrepared.Failed -> {
                notifications.postReadoutFailure(
                    title = "予定",
                    message = "予定の取得に失敗しました。",
                )
                return
            }
            is CalendarPrepared.Ready -> loaded.announcement
        }
        notifications.post(
            category = NotificationCategoryCalendar,
            title = announcement.title,
            body = announcement.body,
            speechText = announcement.speechText,
            calendarItems = announcement.items,
            eventCount = announcement.eventCount,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private suspend fun loadPrepared(
        kind: CalendarAnnounceKind,
        now: LocalDateTime,
    ): CalendarPrepared {
        if (!InternetConnectivity.hasInternet()) {
            return CalendarPrepared.Offline
        }
        if (!repository.hasPermission()) {
            return CalendarPrepared.Permission
        }
        return try {
            val calendars = withContext(Dispatchers.IO) { repository.loadCalendars() }
            val enabledIds = withContext(Dispatchers.Main.immediate) {
                settings.enabledIdsAmong(calendars)
            }
            if (enabledIds.isEmpty()) {
                return CalendarPrepared.NoCalendars
            }
            val events = withContext(Dispatchers.IO) {
                repository.loadEventsForAnnounce(kind, enabledIds, now)
            }
            CalendarPrepared.Ready(buildCalendarAnnouncement(kind, events))
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            CalendarPrepared.Permission
        } catch (_: Exception) {
            CalendarPrepared.Failed
        }
    }
}

private data class CalendarPrepareKey(
    val kind: CalendarAnnounceKind,
    val day: LocalDate,
)

private sealed class CalendarPrepared {
    data class Ready(val announcement: CalendarAnnouncement) : CalendarPrepared()
    data object Offline : CalendarPrepared()
    data object NoCalendars : CalendarPrepared()
    data object Permission : CalendarPrepared()
    data object Failed : CalendarPrepared()
}

@Composable
fun rememberCalendarAnnouncer(
    settings: CalendarSettings,
    notifications: NotificationCenter,
): CalendarAnnouncer {
    val context = LocalContext.current
    return remember(settings, notifications) {
        CalendarAnnouncer(
            context = context.applicationContext,
            settings = settings,
            notifications = notifications,
        )
    }
}
