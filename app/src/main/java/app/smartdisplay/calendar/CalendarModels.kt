package app.smartdisplay.calendar

import java.time.LocalDateTime

data class DeviceCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String?,
    val color: Int?,
    val enabled: Boolean,
)

data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val calendarName: String?,
)

data class CalendarDaySections(
    val upcomingToday: List<CalendarEvent>,
    val tomorrow: List<CalendarEvent>,
    /** 今日・明日を除く、一週間以内の予定 */
    val weekAhead: List<CalendarEvent>,
)

fun CalendarEvent.displayTitle(): String {
    return title.ifBlank { "無題の予定" }
}
