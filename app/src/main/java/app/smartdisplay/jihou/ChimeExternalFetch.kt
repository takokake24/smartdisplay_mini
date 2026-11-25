package app.smartdisplay.jihou

import app.smartdisplay.brief.isMorningBriefSlot
import app.smartdisplay.calendar.CalendarAnnounceKind
import app.smartdisplay.calendar.CalendarAnnouncer
import app.smartdisplay.calendar.calendarAnnounceKindForChime
import app.smartdisplay.expiration.ExpirationAnnouncer
import app.smartdisplay.memorial.MemorialAnnouncer
import app.smartdisplay.memorial.memorialsForChime
import app.smartdisplay.quote.QuoteAnnouncer
import app.smartdisplay.steps.StepsAnnouncer
import app.smartdisplay.transit.TransitMonitor
import app.smartdisplay.weather.WeatherAnnounceKind
import app.smartdisplay.weather.WeatherAnnouncer
import app.smartdisplay.weather.weatherAnnounceKindForChime
import java.time.LocalDateTime

/**
 * 時報の音が鳴り始めた時点で、このあと読む外部情報の取得を始める。
 * 時刻の読み上げが終わってから取りにいくと、案内の前に待ちができる。
 */
fun beginChimeExternalFetch(
    time: LocalDateTime,
    weatherAnnouncer: WeatherAnnouncer,
    calendarAnnouncer: CalendarAnnouncer,
    memorialAnnouncer: MemorialAnnouncer,
    quoteAnnouncer: QuoteAnnouncer,
    stepsAnnouncer: StepsAnnouncer,
    expirationAnnouncer: ExpirationAnnouncer,
    transitMonitor: TransitMonitor,
    sunsetAnnouncer: SunsetAnnouncer,
) {
    val morning = isMorningBriefSlot(time)
    val sunset = sunsetAnnouncer.matchesChime(time)
    val scheduledWeather = weatherAnnounceKindForChime(time)
    val scheduledCalendar = calendarAnnounceKindForChime(time)
    val weatherKind = when {
        morning -> WeatherAnnounceKind.Today
        scheduledWeather != null -> scheduledWeather
        sunset -> WeatherAnnounceKind.RestOfToday
        else -> null
    }
    val calendarKind = when {
        morning -> CalendarAnnounceKind.Today
        scheduledCalendar != null -> scheduledCalendar
        sunset -> CalendarAnnounceKind.RestOfToday
        else -> null
    }
    if (weatherKind != null) {
        weatherAnnouncer.prepare(weatherKind, time, includeIndoorOutdoor = true)
    }
    if (calendarKind != null) {
        calendarAnnouncer.prepare(calendarKind, time)
    }
    if (morning) {
        transitMonitor.prepareForAnnouncement()
    }
    if (memorialsForChime(time.hour, time.minute)) {
        memorialAnnouncer.prepare(time.toLocalDate())
    }
    if (time.hour == 12 && time.minute == 0) {
        quoteAnnouncer.prepare(time.toLocalDate())
    }
    if (time.hour == 23 && time.minute == 30) {
        stepsAnnouncer.prepare(time)
    }
    val needsExpiration = morning ||
        (time.hour == 12 && time.minute == 0) ||
        (time.hour == 23 && time.minute == 30)
    if (needsExpiration) {
        expirationAnnouncer.prepare(time.toLocalDate())
    }
    if (sunset) {
        sunsetAnnouncer.prepareCurtain()
    }
}
