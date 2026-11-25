package app.smartdisplay.weather

import kotlin.math.roundToInt

data class WeatherAnnouncement(
    val title: String,
    val body: String,
    val speechText: String,
    val snapshot: WeatherSnapshot,
)

fun buildWeatherAnnouncement(
    snapshot: WeatherSnapshot,
    current: CurrentWeather? = null,
): WeatherAnnouncement {
    val weather = snapshot.weatherText
    val max = snapshot.temperatureMaxC.roundToInt()
    val min = snapshot.temperatureMinC.roundToInt()
    val title = when (snapshot.label) {
        "今日" -> "今日の天気"
        "このあと" -> "このあとの天気"
        "明日" -> "明日の天気"
        else -> "${snapshot.label}の天気"
    }
    val forecastSpeech =
        "${snapshot.label}の天気は${weather}、最高気温は${max}度、最低気温は${min}度です。"
    val speech = if (current != null && snapshot.label != "明日") {
        val currentTemp = current.temperatureC.roundToInt()
        "現在の天気は${current.weatherText}、気温は${currentTemp}度です。$forecastSpeech"
    } else {
        forecastSpeech
    }
    val body = buildString {
        if (current != null && snapshot.label != "明日") {
            appendLine("現在 ${current.weatherText} ${current.temperatureC.roundToInt()}℃")
        }
        appendLine(weather)
        appendLine("最高 $max℃")
        append("最低 $min℃")
    }
    val snapshotWithCurrent = if (current != null && snapshot.label != "明日") {
        snapshot.copy(current = current)
    } else {
        snapshot
    }
    return WeatherAnnouncement(
        title = title,
        body = body,
        speechText = speech,
        snapshot = snapshotWithCurrent,
    )
}

fun indoorOutdoorSpeech(climate: IndoorOutdoorClimate): String {
    val indoor = climate.indoorTemperatureC.roundToInt()
    val outdoor = climate.outdoorTemperatureC.roundToInt()
    return "現在の室内の温度は${indoor}度、湿度は${climate.indoorHumidityPercent}％です。" +
        "外の温度は${outdoor}度、湿度は${climate.outdoorHumidityPercent}％です。"
}

const val IndoorOutdoorFailureSpeech = "温湿度計からの情報取得に失敗しました。"
const val IndoorOutdoorFailureLabel = "温湿度の取得に失敗しました。"

fun withIndoorOutdoor(
    announcement: WeatherAnnouncement,
    climate: IndoorOutdoorClimate?,
): WeatherAnnouncement {
    val notice = if (climate == null) {
        IndoorOutdoorNotice.Unavailable
    } else {
        IndoorOutdoorNotice.Available(climate)
    }
    val extraSpeech = when (notice) {
        IndoorOutdoorNotice.Unavailable -> IndoorOutdoorFailureSpeech
        is IndoorOutdoorNotice.Available -> indoorOutdoorSpeech(notice.climate)
    }
    val extraBody = when (notice) {
        IndoorOutdoorNotice.Unavailable -> IndoorOutdoorFailureLabel
        is IndoorOutdoorNotice.Available -> indoorOutdoorBody(notice.climate)
    }
    return announcement.copy(
        body = announcement.body + "\n" + extraBody,
        speechText = announcement.speechText + extraSpeech,
        snapshot = announcement.snapshot.copy(indoorOutdoor = notice),
    )
}

private fun indoorOutdoorBody(climate: IndoorOutdoorClimate): String {
    val indoor = climate.indoorTemperatureC.roundToInt()
    val outdoor = climate.outdoorTemperatureC.roundToInt()
    return "現在の温湿度\n室内 ${indoor}℃ ${climate.indoorHumidityPercent}%\n" +
        "室外 ${outdoor}℃ ${climate.outdoorHumidityPercent}%"
}
