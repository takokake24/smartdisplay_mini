package app.smartdisplay.weather

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

fun daylightRemainingLabel(
    now: LocalDateTime,
    sunrise: LocalTime?,
    sunset: LocalTime?,
): String? {
    if (sunrise == null || sunset == null) {
        return null
    }
    val rise = now.toLocalDate().atTime(sunrise)
    val set = now.toLocalDate().atTime(sunset)
    return when {
        now.isBefore(rise) -> "日の出まであと${formatRemaining(ChronoUnit.MINUTES.between(now, rise))}"
        now.isBefore(set) -> "日の入りまであと${formatRemaining(ChronoUnit.MINUTES.between(now, set))}"
        else -> "日の入り済み"
    }
}

enum class SunClockLead {
    Sunrise,
    Sunset,
}

/** 日の出の90分前なら日の出、日の入りの90分前なら日の入り。その分の0秒で消す。 */
fun sunClockLead(
    now: LocalDateTime,
    sunrise: LocalTime?,
    sunset: LocalTime?,
): SunClockLead? {
    val date = now.toLocalDate()
    if (sunrise != null && isInSunClockLeadWindow(now, date.atTime(sunrise))) {
        return SunClockLead.Sunrise
    }
    if (sunset != null && isInSunClockLeadWindow(now, date.atTime(sunset))) {
        return SunClockLead.Sunset
    }
    return null
}

private fun isInSunClockLeadWindow(now: LocalDateTime, event: LocalDateTime): Boolean {
    val start = event.minusMinutes(SunClockLeadMinutes)
    val end = event.truncatedTo(ChronoUnit.MINUTES)
    return !now.isBefore(start) && now.isBefore(end)
}

private const val SunClockLeadMinutes = 90L

private fun formatRemaining(totalMinutes: Long): String {
    val minutes = totalMinutes.coerceAtLeast(0)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours > 0L && rest > 0L -> "${hours}時間${rest}分"
        hours > 0L -> "${hours}時間"
        else -> "${rest}分"
    }
}
