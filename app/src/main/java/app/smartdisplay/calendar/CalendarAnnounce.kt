package app.smartdisplay.calendar

import app.smartdisplay.notification.CalendarNotificationItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MaxDetailedTimedEvents = 3

data class CalendarAnnouncement(
    val title: String,
    val body: String,
    val speechText: String,
    val items: List<CalendarNotificationItem>,
    val eventCount: Int,
)

fun buildCalendarAnnouncement(
    kind: CalendarAnnounceKind,
    events: List<CalendarEvent>,
): CalendarAnnouncement {
    val allDay = events.filter { it.allDay }
    val timed = events.filter { !it.allDay }
    val total = events.size
    val opener = if (total == 0) {
        when (kind) {
            CalendarAnnounceKind.Today -> "今日登録されている予定はありません。"
            CalendarAnnounceKind.RestOfToday -> "このあと登録されている予定はありません。"
            CalendarAnnounceKind.Tomorrow -> "明日登録されている予定はありません。"
        }
    } else {
        when (kind) {
            CalendarAnnounceKind.Today -> "今日は、${total}件の予定が登録されています。"
            CalendarAnnounceKind.RestOfToday -> "このあとは、${total}件の予定が登録されています。"
            CalendarAnnounceKind.Tomorrow -> "明日は、${total}件の予定が登録されています。"
        }
    }
    val title = when (kind) {
        CalendarAnnounceKind.Today -> "今日の予定"
        CalendarAnnounceKind.RestOfToday -> "このあとの予定"
        CalendarAnnounceKind.Tomorrow -> "明日の予定"
    }

    val speech = buildString {
        append(opener)
        if (total == 0) {
            return@buildString
        }
        if (allDay.isNotEmpty()) {
            append(allDaySpeech(allDay))
        }
        val detailed = timed.take(MaxDetailedTimedEvents)
        detailed.forEachIndexed { index, event ->
            val prefix = if (index == 0) "最初は" else "その次は"
            append("${prefix}${speechTime(event.start)}から、${event.displayTitle()}という予定です。")
        }
        val remaining = timed.size - detailed.size
        if (remaining > 0) {
            append("そのほか${remaining}件の予定があります。")
        }
    }

    val items = buildList {
        allDay.forEach { event ->
            add(
                CalendarNotificationItem(
                    timeLabel = "終日",
                    title = event.displayTitle(),
                    allDay = true,
                ),
            )
        }
        timed.forEach { event ->
            add(
                CalendarNotificationItem(
                    timeLabel = displayTime(event.start),
                    title = event.displayTitle(),
                    allDay = false,
                ),
            )
        }
    }

    val body = buildString {
        appendLine("${total}件")
        items.forEach { item ->
            appendLine("${item.timeLabel}  ${item.title}")
        }
    }.trimEnd()

    return CalendarAnnouncement(
        title = title,
        body = body,
        speechText = speech,
        items = items,
        eventCount = total,
    )
}

private fun allDaySpeech(events: List<CalendarEvent>): String {
    val names = events.map { it.displayTitle() }
    return when (names.size) {
        1 -> "終日の予定は、${names[0]}です。"
        2 -> "終日の予定は、${names[0]}と${names[1]}です。"
        else -> {
            val head = names.dropLast(1).joinToString("、")
            "終日の予定は、${head}、および${names.last()}です。"
        }
    }
}

private fun speechTime(time: LocalDateTime): String {
    return if (time.minute == 0) {
        "${time.hour}時"
    } else {
        "${time.hour}時${time.minute}分"
    }
}

private fun displayTime(time: LocalDateTime): String {
    return time.format(DateTimeFormatter.ofPattern("H:mm"))
}

fun calendarAnnounceKindForChime(time: LocalDateTime): CalendarAnnounceKind? {
    return when {
        time.hour == 12 && time.minute == 0 -> CalendarAnnounceKind.RestOfToday
        time.hour == 23 && time.minute == 30 -> CalendarAnnounceKind.Tomorrow
        else -> null
    }
}
