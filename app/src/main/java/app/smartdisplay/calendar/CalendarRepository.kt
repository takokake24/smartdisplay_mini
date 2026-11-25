package app.smartdisplay.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class CalendarRepository(
    context: Context,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    private val appContext = context.applicationContext

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun loadCalendars(): List<DeviceCalendar> {
        if (!hasPermission()) {
            return emptyList()
        }
        val resolver = appContext.contentResolver
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
        )
        val result = mutableListOf<DeviceCalendar>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val colorIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
            val visibleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)
            while (cursor.moveToNext()) {
                if (cursor.getInt(visibleIdx) == 0) {
                    continue
                }
                result += DeviceCalendar(
                    id = cursor.getLong(idIdx),
                    displayName = cursor.getString(nameIdx).orEmpty().ifBlank { "カレンダー" },
                    accountName = cursor.getString(accountIdx),
                    color = if (cursor.isNull(colorIdx)) null else cursor.getInt(colorIdx),
                    enabled = true,
                )
            }
        }
        return result
    }

    fun loadEvents(
        calendarIds: Set<Long>,
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime,
    ): List<CalendarEvent> {
        return queryEvents(
            calendarIds = calendarIds,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            skipCancelledOrDeclined = false,
        )
    }

    /** 直前通知用。キャンセル済みと欠席は含めない。 */
    fun loadSoonEvents(
        calendarIds: Set<Long>,
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime,
    ): List<CalendarEvent> {
        return queryEvents(
            calendarIds = calendarIds,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            skipCancelledOrDeclined = true,
        )
    }

    private fun queryEvents(
        calendarIds: Set<Long>,
        rangeStart: LocalDateTime,
        rangeEnd: LocalDateTime,
        skipCancelledOrDeclined: Boolean,
    ): List<CalendarEvent> {
        if (!hasPermission() || calendarIds.isEmpty()) {
            return emptyList()
        }
        val resolver = appContext.contentResolver
        val startMillis = rangeStart.atZone(zoneId).toInstant().toEpochMilli()
        val endMillis = rangeEnd.atZone(zoneId).toInstant().toEpochMilli()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
        val selection = buildString {
            append("${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})")
            if (skipCancelledOrDeclined) {
                append(" AND (")
                append(CalendarContract.Instances.STATUS)
                append(" IS NULL OR ")
                append(CalendarContract.Instances.STATUS)
                append(" != ")
                append(CalendarContract.Events.STATUS_CANCELED)
                append(") AND (")
                append(CalendarContract.Instances.SELF_ATTENDEE_STATUS)
                append(" IS NULL OR ")
                append(CalendarContract.Instances.SELF_ATTENDEE_STATUS)
                append(" != ")
                append(CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
                append(")")
            }
        }
        val events = mutableListOf<CalendarEvent>()
        resolver.query(
            uri,
            projection,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val calNameIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val allDay = cursor.getInt(allDayIdx) == 1
                val begin = cursor.getLong(beginIdx)
                val end = cursor.getLong(endIdx)
                events += CalendarEvent(
                    id = cursor.getLong(idIdx),
                    calendarId = cursor.getLong(calIdx),
                    title = cursor.getString(titleIdx).orEmpty(),
                    allDay = allDay,
                    start = millisToDateTime(begin, allDay, isEnd = false),
                    end = millisToDateTime(end, allDay, isEnd = true),
                    calendarName = cursor.getString(calNameIdx),
                )
            }
        }
        return events.distinctBy { "${it.id}-${it.start}-${it.end}" }
    }

    fun loadMenuSections(
        calendarIds: Set<Long>,
        now: LocalDateTime = LocalDateTime.now(zoneId),
    ): CalendarDaySections {
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val weekFrom = today.plusDays(2)
        val weekTo = today.plusDays(6)
        val weekEnd = weekTo.plusDays(1).atStartOfDay()
        val events = loadEvents(
            calendarIds = calendarIds,
            rangeStart = today.atStartOfDay(),
            rangeEnd = weekEnd,
        )
        val upcomingToday = events.filter { event ->
            overlapsDay(event, today) && (event.allDay || !event.end.isBefore(now))
        }.sortedWith(eventComparator())
        val tomorrowEvents = events.filter { event ->
            overlapsDay(event, tomorrow)
        }.sortedWith(eventComparator())
        val weekAhead = events.filter { event ->
            overlapsDateRange(event, weekFrom, weekTo)
        }.sortedWith(eventComparator())
        return CalendarDaySections(
            upcomingToday = upcomingToday,
            tomorrow = tomorrowEvents,
            weekAhead = weekAhead,
        )
    }

    fun loadEventsForAnnounce(
        kind: CalendarAnnounceKind,
        calendarIds: Set<Long>,
        now: LocalDateTime = LocalDateTime.now(zoneId),
    ): List<CalendarEvent> {
        return when (kind) {
            CalendarAnnounceKind.Today -> {
                val today = now.toLocalDate()
                loadEvents(calendarIds, today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                    .filter { overlapsDay(it, today) }
                    .sortedWith(eventComparator())
            }
            CalendarAnnounceKind.RestOfToday -> {
                val today = now.toLocalDate()
                loadEvents(calendarIds, now, today.plusDays(1).atStartOfDay())
                    .filter { overlapsDay(it, today) && (it.allDay || !it.end.isBefore(now)) }
                    .sortedWith(eventComparator())
            }
            CalendarAnnounceKind.Tomorrow -> {
                val tomorrow = now.toLocalDate().plusDays(1)
                loadEvents(calendarIds, tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay())
                    .filter { overlapsDay(it, tomorrow) }
                    .sortedWith(eventComparator())
            }
        }
    }

    private fun overlapsDay(event: CalendarEvent, day: LocalDate): Boolean {
        if (event.allDay) {
            val startDay = event.start.toLocalDate()
            // Instances の終日 END は翌日 0:00 になりやすい
            val endDayExclusive = event.end.toLocalDate()
            return !day.isBefore(startDay) && day.isBefore(endDayExclusive)
        }
        val startDay = event.start.toLocalDate()
        val endDay = if (event.end.toLocalTime() == LocalTime.MIDNIGHT && event.end.isAfter(event.start)) {
            event.end.toLocalDate().minusDays(1)
        } else {
            event.end.toLocalDate()
        }
        return !day.isBefore(startDay) && !day.isAfter(endDay)
    }

    private fun overlapsDateRange(
        event: CalendarEvent,
        from: LocalDate,
        toInclusive: LocalDate,
    ): Boolean {
        var day = from
        while (!day.isAfter(toInclusive)) {
            if (overlapsDay(event, day)) {
                return true
            }
            day = day.plusDays(1)
        }
        return false
    }

    private fun eventComparator(): Comparator<CalendarEvent> {
        return compareBy<CalendarEvent> { !it.allDay }
            .thenBy { it.start }
            .thenBy { it.displayTitle() }
    }

    private fun millisToDateTime(millis: Long, allDay: Boolean, isEnd: Boolean): LocalDateTime {
        if (!allDay) {
            return Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDateTime()
        }
        // 終日は UTC 日付として扱う
        val utcDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
        return if (isEnd) {
            utcDate.atStartOfDay()
        } else {
            utcDate.atStartOfDay()
        }
    }
}
