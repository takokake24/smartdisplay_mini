package app.smartdisplay.calendar

import app.smartdisplay.notification.CalendarNotificationItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val CalendarSoonLeadMinutes = 15

data class CalendarSoonAnnouncement(
    val title: String,
    val body: String,
    val speechText: String,
    val items: List<CalendarNotificationItem>,
    val eventCount: Int,
    val keys: List<String>,
)

fun calendarSoonKey(event: CalendarEvent): String = "${event.id}|${event.start}"

/** 開始までの分。15分より先、開始済みは null。端数は切り上げる。 */
fun calendarSoonMinutes(now: LocalDateTime, start: LocalDateTime): Int? {
    if (!start.isAfter(now)) {
        return null
    }
    val seconds = ChronoUnit.SECONDS.between(now, start)
    if (seconds <= 0L) {
        return null
    }
    val minutes = ((seconds + 59) / 60).toInt()
    if (minutes > CalendarSoonLeadMinutes) {
        return null
    }
    return minutes
}

fun dueCalendarSoonAnnouncements(
    events: List<CalendarEvent>,
    now: LocalDateTime,
    announcedKeys: Set<String>,
): List<CalendarSoonAnnouncement> {
    val due = events.mapNotNull { event ->
        if (event.allDay) {
            return@mapNotNull null
        }
        val minutes = calendarSoonMinutes(now, event.start) ?: return@mapNotNull null
        val key = calendarSoonKey(event)
        if (key in announcedKeys) {
            return@mapNotNull null
        }
        DueSoonEvent(event, minutes, key)
    }
    return due
        .groupBy { it.event.start }
        .entries
        .sortedBy { it.key }
        .map { (_, group) ->
            val sorted = group.sortedWith(compareBy({ it.event.displayTitle() }, { it.event.id }))
            buildCalendarSoonAnnouncement(
                events = sorted.map { it.event },
                minutesAhead = sorted.first().minutes,
                keys = sorted.map { it.key },
            )
        }
}

fun calendarSoonTestAnnouncement(
    now: LocalDateTime = LocalDateTime.now(),
): CalendarSoonAnnouncement {
    val start = now.plusMinutes(CalendarSoonLeadMinutes.toLong()).withSecond(0).withNano(0)
    val event = CalendarEvent(
        id = -1,
        calendarId = -1,
        title = "予定のテスト",
        allDay = false,
        start = start,
        end = start.plusHours(1),
        calendarName = null,
    )
    return buildCalendarSoonAnnouncement(
        events = listOf(event),
        minutesAhead = CalendarSoonLeadMinutes,
    )
}

fun buildCalendarSoonAnnouncement(
    events: List<CalendarEvent>,
    minutesAhead: Int,
    keys: List<String> = events.map { calendarSoonKey(it) },
): CalendarSoonAnnouncement {
    val items = events.map { event ->
        CalendarNotificationItem(
            timeLabel = event.start.format(SoonClockFormat),
            title = event.displayTitle(),
            allDay = false,
        )
    }
    val body = buildString {
        appendLine("${events.size}件")
        items.forEach { item ->
            appendLine("${item.timeLabel}  ${item.title}")
        }
    }.trimEnd()
    return CalendarSoonAnnouncement(
        title = "まもなく予定",
        body = body,
        speechText = "${joinSoonTitles(events)}まで、あと${minutesAhead}分です。",
        items = items,
        eventCount = events.size,
        keys = keys,
    )
}

private data class DueSoonEvent(
    val event: CalendarEvent,
    val minutes: Int,
    val key: String,
)

private fun joinSoonTitles(events: List<CalendarEvent>): String {
    val names = events.map { it.displayTitle() }
    return when (names.size) {
        1 -> names[0]
        2 -> "${names[0]}と${names[1]}"
        else -> {
            val head = names.dropLast(1).joinToString("、")
            "${head}、および${names.last()}"
        }
    }
}

private val SoonClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
