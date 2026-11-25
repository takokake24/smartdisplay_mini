package app.smartdisplay.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryWeather
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.switchbot.SwitchBotClient
import app.smartdisplay.switchbot.SwitchBotMeterSample
import app.smartdisplay.switchbot.switchBotConfigured
import app.smartdisplay.switchbot.switchBotSecret
import app.smartdisplay.switchbot.switchBotToken
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import app.smartdisplay.util.PreparedFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class WeatherAnnouncer(
    private val repository: WeatherRepository,
    private val notifications: NotificationCenter,
) {
    private val prepared = PreparedFetch<WeatherPrepared>()

    suspend fun onChimeFinished(time: LocalDateTime) {
        val kind = weatherAnnounceKindForChime(time) ?: return
        postAnnouncement(kind, now = time, includeIndoorOutdoor = true)
    }

    /** 時報の鳴り始めに呼ぶ。案内の時点では、ここで始めた取得を使う。 */
    fun prepare(kind: WeatherAnnounceKind, now: LocalDateTime, includeIndoorOutdoor: Boolean) {
        prepared.start(WeatherPrepareKey(kind, now.toLocalDate(), includeIndoorOutdoor)) {
            fetchWeather(includeIndoorOutdoor)
        }
    }

    suspend fun postAnnouncement(
        kind: WeatherAnnounceKind,
        now: LocalDateTime = LocalDateTime.now(),
        includeIndoorOutdoor: Boolean = false,
    ) {
        val key = WeatherPrepareKey(kind, now.toLocalDate(), includeIndoorOutdoor)
        val loaded = prepared.take(key)?.takeUnless { it is WeatherPrepared.Offline }
            ?: fetchWeather(includeIndoorOutdoor)
        postWeather(kind, now, loaded)
    }

    private suspend fun fetchWeather(includeIndoorOutdoor: Boolean): WeatherPrepared {
        if (!InternetConnectivity.hasInternet()) {
            return WeatherPrepared.Offline
        }
        return coroutineScope {
            val climateTask = if (includeIndoorOutdoor) {
                async(Dispatchers.IO) { fetchLivingAndOutdoor() }
            } else {
                null
            }
            val result = withContext(Dispatchers.IO) {
                repository.loadForecastResult(
                    LocalDateTime.now(),
                    reportFailure = false,
                    forceRefresh = true,
                )
            }
            WeatherPrepared.Loaded(
                result = result,
                climate = climateTask?.await(),
                climateRequested = includeIndoorOutdoor,
            )
        }
    }

    private suspend fun postWeather(
        kind: WeatherAnnounceKind,
        now: LocalDateTime,
        loaded: WeatherPrepared,
    ) {
        val data = loaded as? WeatherPrepared.Loaded ?: return
        val result = data.result
        if (!InternetConnectivity.hasInternet() || result.error is NoInternetException) {
            return
        }
        val forecast = result.forecast
        if (forecast == null) {
            notifications.postReadoutFailure(
                title = "天気情報",
                message = "天気情報の取得に失敗しました。",
            )
            return
        }
        if (result.fetchFailed) {
            FetchFailureNotifier.report(listOf("天気予報"))
        }
        val announcement = runCatching {
            val snapshot = repository.snapshotForAnnounce(kind, forecast, now)
            val current = if (kind != WeatherAnnounceKind.Tomorrow) {
                repository.currentWeather(forecast, now)
            } else {
                null
            }
            buildWeatherAnnouncement(snapshot, current = current)
        }.getOrElse {
            notifications.postReadoutFailure(
                title = "天気情報",
                message = "天気情報の取得に失敗しました。",
            )
            return
        }
        val posted = if (!data.climateRequested) {
            announcement
        } else {
            val climate = data.climate?.let { (indoor, outdoor) ->
                IndoorOutdoorClimate(
                    indoorTemperatureC = indoor.temperatureC,
                    indoorHumidityPercent = indoor.humidityPercent,
                    outdoorTemperatureC = outdoor.temperatureC,
                    outdoorHumidityPercent = outdoor.humidityPercent,
                )
            }
            withIndoorOutdoor(announcement, climate)
        }

        notifications.post(
            category = NotificationCategoryWeather,
            title = posted.title,
            body = posted.body,
            speechText = posted.speechText,
            weatherSnapshot = posted.snapshot,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private fun fetchLivingAndOutdoor(): Pair<SwitchBotMeterSample, SwitchBotMeterSample>? {
        if (!switchBotConfigured) {
            return null
        }
        return try {
            SwitchBotClient().fetchLivingAndOutdoor(switchBotToken, switchBotSecret)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

private data class WeatherPrepareKey(
    val kind: WeatherAnnounceKind,
    val day: LocalDate,
    val indoor: Boolean,
)

private sealed class WeatherPrepared {
    data class Loaded(
        val result: WeatherLoadResult,
        val climate: Pair<SwitchBotMeterSample, SwitchBotMeterSample>?,
        val climateRequested: Boolean,
    ) : WeatherPrepared()

    data object Offline : WeatherPrepared()
}

@Composable
fun rememberWeatherAnnouncer(
    notifications: NotificationCenter,
    repository: WeatherRepository,
): WeatherAnnouncer {
    return remember(notifications, repository) {
        WeatherAnnouncer(
            repository = repository,
            notifications = notifications,
        )
    }
}
