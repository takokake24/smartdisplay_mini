package app.smartdisplay.sun

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** その日の日の出・日の入り。極夜・白夜などで出ないときは null。 */
data class SunTimes(
    val sunrise: LocalTime,
    val sunset: LocalTime,
)

/**
 * 指定位置の日の出・日の入りを、端末のタイムゾーンで計算する。
 * NOAA の近似式。通信は使わず、表示用に分単位で足りる精度。
 * 緯度経度が無いときは小平市役所付近を使う。
 */
fun sunTimesFor(
    date: LocalDate,
    latitudeDegrees: Double? = null,
    longitudeDegrees: Double? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
): SunTimes? {
    val latitudeDeg = latitudeDegrees ?: FallbackLatitude
    val longitudeDeg = longitudeDegrees ?: FallbackLongitude
    val latitude = Math.toRadians(latitudeDeg)
    val gamma = 2.0 * Math.PI / daysInYear(date) * (date.dayOfYear - 1)
    val equationOfTime = 229.18 * (
        0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2.0 * gamma) -
            0.040849 * sin(2.0 * gamma)
        )
    val declination = 0.006918 -
        0.399912 * cos(gamma) +
        0.070257 * sin(gamma) -
        0.006758 * cos(2.0 * gamma) +
        0.000907 * sin(2.0 * gamma) -
        0.002697 * cos(3.0 * gamma) +
        0.00148 * sin(3.0 * gamma)
    val cosHourAngle = cos(Math.toRadians(SunriseZenithDegrees)) /
        (cos(latitude) * cos(declination)) -
        tan(latitude) * tan(declination)
    if (cosHourAngle !in -1.0..1.0) {
        return null
    }
    val hourAngle = Math.toDegrees(acos(cosHourAngle))
    val zoneOffsetMinutes = zoneId.rules
        .getOffset(date.atTime(12, 0).atZone(zoneId).toInstant())
        .totalSeconds / 60.0
    val sunriseMinutes = 720.0 - 4.0 * (longitudeDeg + hourAngle) - equationOfTime + zoneOffsetMinutes
    val sunsetMinutes = 720.0 - 4.0 * (longitudeDeg - hourAngle) - equationOfTime + zoneOffsetMinutes
    return SunTimes(
        sunrise = minutesToTime(sunriseMinutes),
        sunset = minutesToTime(sunsetMinutes),
    )
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

private fun minutesToTime(minutes: Double): LocalTime {
    val wrapped = ((minutes % MinutesPerDay) + MinutesPerDay) % MinutesPerDay
    return LocalTime.ofSecondOfDay((wrapped * 60.0).toLong().coerceIn(0L, 86_399L))
}

private fun daysInYear(date: LocalDate): Int = if (date.isLeapYear) 366 else 365

private const val SunClockLeadMinutes = 90L

/** 現在地が取れないときの控え（小平市役所付近）。 */
private const val FallbackLatitude = 35.7285
private const val FallbackLongitude = 139.4777

/** 日の出・日の入りの天頂角（大気差と太陽半径を含む）。 */
private const val SunriseZenithDegrees = 90.833
private const val MinutesPerDay = 1440.0
