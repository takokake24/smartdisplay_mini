package app.smartdisplay.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NetworkLog
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 小平市の天気予報（Open-Meteo）。
 *
 * 通信は、起動時と日付の変わり目、天気案内、メニューの天気予報、
 * まもなく雨・雪・雷のための30分間隔だけ行う。
 * 日の出・日の入りは、同じ日に取った結果を使う。
 */
class WeatherRepository {
    @Volatile
    private var cached: WeatherForecast? = null
    private val fetchLock = Any()

    private val _lastFetchedAt = MutableStateFlow<LocalDateTime?>(null)
    val lastFetchedAt: StateFlow<LocalDateTime?> = _lastFetchedAt.asStateFlow()

    /** 今日取得した予報。通信しない。 */
    fun cachedForecast(now: LocalDateTime = LocalDateTime.now()): WeatherForecast? {
        val existing = cached ?: return null
        if (existing.fetchedAt.toLocalDate() != now.toLocalDate()) {
            return null
        }
        return existing
    }

    /** 取得日が今日でない予報だけ捨てる（設定など永続データには触らない）。 */
    fun pruneExpired(now: LocalDateTime = LocalDateTime.now()) {
        synchronized(fetchLock) {
            val existing = cached ?: return
            if (existing.fetchedAt.toLocalDate() != now.toLocalDate()) {
                cached = null
            }
        }
    }

    /**
     * 今日のキャッシュがあるときは通信しない。[forceRefresh] のときは取り直す。
     * 通信に失敗してもキャッシュがあれば [WeatherLoadResult.forecast] に載せ、[WeatherLoadResult.fetchFailed] を true にする。
     */
    fun loadForecastResult(
        now: LocalDateTime = LocalDateTime.now(),
        reportFailure: Boolean = true,
        forceRefresh: Boolean = false,
    ): WeatherLoadResult {
        if (!forceRefresh) {
            cachedForecast(now)?.let { hit ->
                return WeatherLoadResult(forecast = hit, fetchFailed = false, error = null)
            }
        }
        synchronized(fetchLock) {
            if (!forceRefresh) {
                cachedForecast(now)?.let { hit ->
                    return WeatherLoadResult(forecast = hit, fetchFailed = false, error = null)
                }
            }
            val existing = cached
            return runCatching {
                val fetched = fetchForecast(now)
                cached = fetched
                _lastFetchedAt.value = fetched.fetchedAt
                FetchFailureNotifier.clear(listOf(FetchFailureLabel))
                WeatherLoadResult(forecast = fetched, fetchFailed = false, error = null)
            }.getOrElse { error ->
                if (reportFailure &&
                    error !is NoInternetException &&
                    InternetConnectivity.hasInternet()
                ) {
                    FetchFailureNotifier.report(listOf(FetchFailureLabel))
                }
                WeatherLoadResult(forecast = existing, fetchFailed = true, error = error)
            }
        }
    }

    fun menuDays(forecast: WeatherForecast, today: LocalDate = LocalDate.now()): List<DailyWeather> {
        return listOf(0, 1, 2).mapNotNull { offset ->
            val date = today.plusDays(offset.toLong())
            forecast.daily.firstOrNull { it.date == date }
        }
    }

    fun currentWeather(
        forecast: WeatherForecast,
        now: LocalDateTime = LocalDateTime.now(),
    ): CurrentWeather? {
        val hourly = forecast.hourly
        if (hourly.isEmpty()) {
            return null
        }
        val currentSlot = hourly
            .filter { !it.time.isAfter(now) }
            .maxByOrNull { it.time }
            ?: hourly.minByOrNull { it.time }
            ?: return null
        return CurrentWeather(
            weatherCode = currentSlot.weatherCode,
            temperatureC = currentSlot.temperatureC,
        )
    }

    fun snapshotForAnnounce(
        kind: WeatherAnnounceKind,
        forecast: WeatherForecast,
        now: LocalDateTime = LocalDateTime.now(),
    ): WeatherSnapshot {
        return when (kind) {
            WeatherAnnounceKind.Today -> {
                val day = requireDaily(forecast, now.toLocalDate())
                WeatherSnapshot(
                    label = "今日",
                    weatherCode = day.weatherCode,
                    temperatureMaxC = day.temperatureMaxC,
                    temperatureMinC = day.temperatureMinC,
                )
            }
            WeatherAnnounceKind.RestOfToday -> {
                val remaining = forecast.hourly.filter {
                    it.time.toLocalDate() == now.toLocalDate() && !it.time.isBefore(now)
                }
                if (remaining.isEmpty()) {
                    val day = requireDaily(forecast, now.toLocalDate())
                    WeatherSnapshot(
                        label = "このあと",
                        weatherCode = day.weatherCode,
                        temperatureMaxC = day.temperatureMaxC,
                        temperatureMinC = day.temperatureMinC,
                    )
                } else {
                    WeatherSnapshot(
                        label = "このあと",
                        weatherCode = dominantWeatherCode(remaining.map { it.weatherCode }),
                        temperatureMaxC = remaining.maxOf { it.temperatureC },
                        temperatureMinC = remaining.minOf { it.temperatureC },
                    )
                }
            }
            WeatherAnnounceKind.Tomorrow -> {
                val day = requireDaily(forecast, now.toLocalDate().plusDays(1))
                WeatherSnapshot(
                    label = "明日",
                    weatherCode = day.weatherCode,
                    temperatureMaxC = day.temperatureMaxC,
                    temperatureMinC = day.temperatureMinC,
                )
            }
        }
    }

    private fun requireDaily(forecast: WeatherForecast, date: LocalDate): DailyWeather {
        return forecast.daily.firstOrNull { it.date == date }
            ?: error("weather missing for $date")
    }

    private fun dominantWeatherCode(codes: List<Int>): Int {
        if (codes.isEmpty()) {
            return 0
        }
        return codes
            .groupingBy { it }
            .eachCount()
            .entries
            .maxWith(compareBy<Map.Entry<Int, Int>>({ it.value }, { weatherSeverity(it.key) }))
            .key
    }

    private fun weatherSeverity(code: Int): Int {
        return when (code) {
            0 -> 0
            1 -> 1
            2 -> 2
            3 -> 3
            45, 48 -> 4
            51, 53, 55, 56, 57 -> 5
            61, 63, 65, 66, 67, 80, 81, 82 -> 6
            71, 73, 75, 77, 85, 86 -> 7
            95, 96, 99 -> 8
            else -> 3
        }
    }

    private fun fetchForecast(now: LocalDateTime): WeatherForecast =
        NetworkLog.trace(ForecastUrl) {
            val connection = (URL(ForecastUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TimeoutMs.toInt()
                readTimeout = TimeoutMs.toInt()
                requestMethod = "GET"
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("weather http $code")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseForecast(body, now)
            } finally {
                connection.disconnect()
            }
        }

    private fun parseForecast(body: String, now: LocalDateTime): WeatherForecast {
        val root = JSONObject(body)
        val daily = root.getJSONObject("daily")
        val dailyTimes = daily.getJSONArray("time")
        val dailyCodes = daily.getJSONArray("weather_code")
        val dailyMax = daily.getJSONArray("temperature_2m_max")
        val dailyMin = daily.getJSONArray("temperature_2m_min")
        val sunrises = daily.optJSONArray("sunrise")
        val sunsets = daily.optJSONArray("sunset")
        val days = buildList {
            for (i in 0 until dailyTimes.length()) {
                add(
                    DailyWeather(
                        date = LocalDate.parse(dailyTimes.getString(i)),
                        weatherCode = dailyCodes.getInt(i),
                        temperatureMaxC = dailyMax.getDouble(i),
                        temperatureMinC = dailyMin.getDouble(i),
                        sunrise = parseSunTime(sunrises, i),
                        sunset = parseSunTime(sunsets, i),
                    ),
                )
            }
        }
        val hourly = root.getJSONObject("hourly")
        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyCodes = hourly.getJSONArray("weather_code")
        val hourlyTemps = hourly.getJSONArray("temperature_2m")
        val precipProb = hourly.optJSONArray("precipitation_probability")
        val precipMm = hourly.optJSONArray("precipitation")
        val snowfall = hourly.optJSONArray("snowfall")
        val hours = buildList {
            for (i in 0 until hourlyTimes.length()) {
                add(
                    HourlyWeather(
                        time = LocalDateTime.parse(hourlyTimes.getString(i), HourlyFormatter),
                        weatherCode = hourlyCodes.getInt(i),
                        temperatureC = hourlyTemps.getDouble(i),
                        precipitationProbability = precipProb.optIntAt(i, 0),
                        precipitationMm = precipMm.optDoubleAt(i, 0.0),
                        snowfallCm = snowfall.optDoubleAt(i, 0.0),
                    ),
                )
            }
        }
        return WeatherForecast(
            daily = days,
            hourly = hours,
            fetchedAt = now,
        )
    }

    private fun parseSunTime(array: JSONArray?, index: Int): LocalTime? {
        if (array == null || index >= array.length() || array.isNull(index)) {
            return null
        }
        val raw = array.optString(index, "").ifBlank { return null }
        return runCatching {
            LocalDateTime.parse(raw, HourlyFormatter).toLocalTime()
        }.recoverCatching {
            LocalTime.parse(raw)
        }.getOrNull()
    }

    private fun JSONArray?.optIntAt(index: Int, default: Int): Int {
        if (this == null || index >= length() || isNull(index)) {
            return default
        }
        return optInt(index, default)
    }

    private fun JSONArray?.optDoubleAt(index: Int, default: Double): Double {
        if (this == null || index >= length() || isNull(index)) {
            return default
        }
        return optDouble(index, default)
    }

    companion object {
        // 小平市役所付近
        private const val Latitude = 35.7285
        private const val Longitude = 139.4777
        private const val FetchFailureLabel = "天気予報"
        private val TimeoutMs = TimeUnit.SECONDS.toMillis(12)
        private val HourlyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val ForecastUrl =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$Latitude&longitude=$Longitude" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&hourly=weather_code,temperature_2m,precipitation_probability,precipitation,snowfall" +
                "&timezone=Asia%2FTokyo&forecast_days=3"
    }
}

/** 起動時と日付の変わり目に、その日の予報を1回取る。失敗している間だけやり直す。 */
@Composable
fun rememberWeatherDailyFetch(repository: WeatherRepository) {
    LaunchedEffect(repository) {
        var retryMs = DailyRetryInitialMs
        while (isActive) {
            val now = LocalDateTime.now()
            if (repository.cachedForecast(now) == null && InternetConnectivity.hasInternet()) {
                withContext(Dispatchers.IO) {
                    repository.loadForecastResult(now)
                }
            }
            if (repository.cachedForecast(LocalDateTime.now()) != null) {
                retryMs = DailyRetryInitialMs
                delay(millisUntilNextDate(LocalDateTime.now()))
            } else {
                delay(retryMs)
                retryMs = (retryMs * 2).coerceAtMost(DailyRetryMaxMs)
            }
        }
    }
}

private fun millisUntilNextDate(now: LocalDateTime): Long {
    val next = now.toLocalDate().plusDays(1).atStartOfDay()
    return Duration.between(now, next).toMillis().coerceAtLeast(1L)
}

private const val DailyRetryInitialMs = 30_000L
private const val DailyRetryMaxMs = 5 * 60_000L
