package app.smartdisplay.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class WeatherAnnounceKind {
    /** 8:00 今日の天気 */
    Today,
    /** 12:00 このあとの天気 */
    RestOfToday,
    /** 23:30 明日の天気 */
    Tomorrow,
}

enum class ImminentWeatherHazard {
    Rain,
    Snow,
    Thunder,
}

data class DailyWeather(
    val date: LocalDate,
    val weatherCode: Int,
    val temperatureMaxC: Double,
    val temperatureMinC: Double,
    val sunrise: LocalTime? = null,
    val sunset: LocalTime? = null,
)

data class HourlyWeather(
    val time: LocalDateTime,
    val weatherCode: Int,
    val temperatureC: Double,
    val precipitationProbability: Int = 0,
    val precipitationMm: Double = 0.0,
    val snowfallCm: Double = 0.0,
)

data class WeatherForecast(
    val daily: List<DailyWeather>,
    val hourly: List<HourlyWeather>,
    val fetchedAt: LocalDateTime,
)

data class WeatherLoadResult(
    val forecast: WeatherForecast?,
    val fetchFailed: Boolean,
    val error: Throwable? = null,
)

data class IndoorOutdoorClimate(
    val indoorTemperatureC: Double,
    val indoorHumidityPercent: Int,
    val outdoorTemperatureC: Double,
    val outdoorHumidityPercent: Int,
)

/** 6時・7時・8時・8:30・9時・12時・23:30・日の入りの天気案内に添える温湿度。未取得の案内では null。 */
sealed class IndoorOutdoorNotice {
    data class Available(val climate: IndoorOutdoorClimate) : IndoorOutdoorNotice()
    data object Unavailable : IndoorOutdoorNotice()
}

data class WeatherSnapshot(
    val label: String,
    val weatherCode: Int,
    val temperatureMaxC: Double,
    val temperatureMinC: Double,
    val current: CurrentWeather? = null,
    val indoorOutdoor: IndoorOutdoorNotice? = null,
) {
    val weatherText: String
        get() = weatherDescriptionJa(weatherCode)
}

data class CurrentWeather(
    val weatherCode: Int,
    val temperatureC: Double,
) {
    val weatherText: String
        get() = weatherDescriptionJa(weatherCode)
}

data class MoonPhaseInfo(
    val label: String,
    val illuminationPercent: Int,
)

fun weatherAnnounceKindForChime(time: LocalDateTime): WeatherAnnounceKind? {
    return when {
        time.hour == 12 && time.minute == 0 -> WeatherAnnounceKind.RestOfToday
        time.hour == 23 && time.minute == 30 -> WeatherAnnounceKind.Tomorrow
        else -> null
    }
}
