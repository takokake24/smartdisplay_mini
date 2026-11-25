package app.smartdisplay.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryWeather
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.util.InternetConnectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class WeatherImminentMonitor(
    private val repository: WeatherRepository,
    private val notifications: NotificationCenter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var seededDate: LocalDate? = null
    private var lastHazard: ImminentWeatherHazard? = null
    private var lastNotifiedHazard: ImminentWeatherHazard? = null
    private var lastNotifiedAt: LocalDateTime? = null

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launch {
            while (isActive) {
                if (InternetConnectivity.hasInternet()) {
                    runCatching { checkOnce() }
                }
                delay(millisUntilNextCheck())
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    suspend fun checkOnce(now: LocalDateTime = LocalDateTime.now()) {
        if (!InternetConnectivity.hasInternet()) {
            return
        }
        val forecast = forecastForCheck(now) ?: return
        val hazard = detectImminentHazard(forecast, now)
        val today = now.toLocalDate()
        if (seededDate != today) {
            lastHazard = hazard
            seededDate = today
            return
        }
        val previous = lastHazard
        lastHazard = hazard
        if (hazard == null) {
            return
        }
        val appeared = previous == null
        val escalated = previous != null && hazard.rank > previous.rank
        if (!appeared && !escalated) {
            return
        }
        if (isInCooldown(hazard, now)) {
            return
        }
        val (title, body, speech) = imminentAlertCopy(hazard)
        notifications.post(
            category = NotificationCategoryWeather,
            title = title,
            body = body,
            speechText = speech,
            alert = true,
            addToHistory = true,
            soundEffect = NotificationSoundEffect.Notice,
            priority = true,
        )
        lastNotifiedHazard = hazard
        lastNotifiedAt = now
    }

    private suspend fun forecastForCheck(now: LocalDateTime): WeatherForecast? {
        val cached = repository.cachedForecast(now) ?: return null
        if (cached.fetchedAt.isAfter(now.minusMinutes(RefreshMinutes))) {
            return cached
        }
        val refreshed = withContext(Dispatchers.IO) {
            repository.loadForecastResult(now, forceRefresh = true).forecast
        }
        return refreshed ?: cached
    }

    private fun millisUntilNextCheck(now: LocalDateTime = LocalDateTime.now()): Long {
        if (seededDate != now.toLocalDate()) {
            return SeedRetryMs
        }
        val fetchedAt = repository.cachedForecast(now)?.fetchedAt ?: return SeedRetryMs
        val untilRefresh = ChronoUnit.MILLIS.between(now, fetchedAt.plusMinutes(RefreshMinutes))
        if (untilRefresh <= 0L) {
            return PollIntervalMs
        }
        return minOf(untilRefresh, PollIntervalMs).coerceAtLeast(1L)
    }

    private fun isInCooldown(hazard: ImminentWeatherHazard, now: LocalDateTime): Boolean {
        val previousHazard = lastNotifiedHazard ?: return false
        val previousAt = lastNotifiedAt ?: return false
        if (ChronoUnit.HOURS.between(previousAt, now) >= CooldownHours) {
            return false
        }
        return hazard.rank <= previousHazard.rank
    }

    companion object {
        private const val PollIntervalMs = 10 * 60_000L
        private const val RefreshMinutes = 30L
        private const val SeedRetryMs = 15_000L
        private const val CooldownHours = 3L
    }
}

@Composable
fun rememberWeatherImminentMonitor(
    notifications: NotificationCenter,
    repository: WeatherRepository,
): WeatherImminentMonitor {
    val monitor = remember(notifications, repository) {
        WeatherImminentMonitor(
            repository = repository,
            notifications = notifications,
        )
    }
    DisposableEffect(monitor) {
        monitor.start()
        onDispose { monitor.release() }
    }
    return monitor
}
