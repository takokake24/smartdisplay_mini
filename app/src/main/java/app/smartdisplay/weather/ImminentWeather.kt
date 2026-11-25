package app.smartdisplay.weather

import java.time.LocalDateTime

val ImminentWeatherHazard.rank: Int
    get() = when (this) {
        ImminentWeatherHazard.Rain -> 1
        ImminentWeatherHazard.Snow -> 2
        ImminentWeatherHazard.Thunder -> 3
    }

fun detectImminentHazard(
    forecast: WeatherForecast,
    now: LocalDateTime = LocalDateTime.now(),
): ImminentWeatherHazard? {
    val windowEnd = now.plusHours(1)
    val hours = forecast.hourly.filter { hour ->
        !hour.time.plusMinutes(59).isBefore(now) && !hour.time.isAfter(windowEnd)
    }
    if (hours.isEmpty()) {
        return null
    }
    if (hours.any { it.weatherCode in ThunderCodes }) {
        return ImminentWeatherHazard.Thunder
    }
    if (hours.any { it.snowfallCm > 0.0 || it.weatherCode in SnowCodes }) {
        return ImminentWeatherHazard.Snow
    }
    if (hours.any {
            it.precipitationMm >= 0.2 ||
                it.precipitationProbability >= 50 ||
                it.weatherCode in RainCodes
        }
    ) {
        return ImminentWeatherHazard.Rain
    }
    return null
}

fun imminentAlertCopy(hazard: ImminentWeatherHazard): Triple<String, String, String> {
    return when (hazard) {
        ImminentWeatherHazard.Rain -> Triple(
            "まもなく雨",
            "これから1時間で雨が降りそうです",
            "これから1時間で雨が降りそうです。",
        )
        ImminentWeatherHazard.Snow -> Triple(
            "まもなく雪",
            "これから1時間で雪が降りそうです",
            "これから1時間で雪が降りそうです。",
        )
        ImminentWeatherHazard.Thunder -> Triple(
            "まもなく雷",
            "これから1時間で雷の恐れがあります",
            "これから1時間で雷の恐れがあります。",
        )
    }
}

private val RainCodes = setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)
private val SnowCodes = setOf(71, 73, 75, 77, 85, 86)
private val ThunderCodes = setOf(95, 96, 99)
