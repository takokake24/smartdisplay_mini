package app.smartdisplay.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicLong

enum class NetworkLogCategory(val label: String) {
    Bus("バス"),
    Weather("天気"),
    Transit("運行情報"),
    Train("鉄道"),
    WeatherWarning("気象警報"),
    Earthquake("地震"),
    SwitchBot("機器"),
    Spotify("音楽"),
    Memorial("記念日"),
    Quote("格言"),
    Expiration("賞味期限"),
}

data class NetworkLogEntry(
    val id: Long,
    val category: NetworkLogCategory,
    val destination: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime,
    val result: String,
)

object NetworkLog {
    /** 初回を含めて最大3回。一時的な通信失敗向け。 */
    private const val MaxAttempts = 3
    private const val RetryDelayMs = 800L

    private val sequence = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<NetworkLogEntry>>(emptyList())
    val entries: StateFlow<List<NetworkLogEntry>> = _entries.asStateFlow()

    /**
     * 通信は完了時に1件だけ記録する（開始時と終了時の二重登録をしない）。
     * 失敗したときは、未接続でなければ最大 [MaxAttempts] 回までやり直す。
     * 操作コマンドは [retry] を false にして、同じ命令を重ねて送らない。
     */
    fun <T> trace(url: String, retry: Boolean = true, block: () -> T): T {
        if (!InternetConnectivity.hasInternet()) {
            throw NoInternetException()
        }
        prune()
        val destination = networkDestination(url)
        val category = networkCategory(url)
        val startedAt = LocalDateTime.now()
        var lastError: Throwable? = null
        val attempts = if (retry) MaxAttempts else 1
        repeat(attempts) { attempt ->
            if (!InternetConnectivity.hasInternet()) {
                throw NoInternetException()
            }
            try {
                val value = block()
                append(
                    category = category,
                    destination = destination,
                    startedAt = startedAt,
                    endedAt = LocalDateTime.now(),
                    result = "成功",
                )
                return value
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw error
            } catch (error: NoInternetException) {
                throw error
            } catch (error: Throwable) {
                lastError = error
                val hasNext = retry && attempt < attempts - 1 && InternetConnectivity.hasInternet()
                if (hasNext) {
                    sleepBeforeRetry(attempt)
                }
            }
        }
        if (!InternetConnectivity.hasInternet()) {
            throw NoInternetException()
        }
        val error = lastError ?: error("通信に失敗しました")
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        append(
            category = category,
            destination = destination,
            startedAt = startedAt,
            endedAt = LocalDateTime.now(),
            result = "失敗（$detail）",
        )
        throw error
    }

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            Thread.sleep(RetryDelayMs * (attempt + 1))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }

    fun prune(now: LocalDateTime = LocalDateTime.now()) {
        val cutoff = now.minusHours(3)
        _entries.update { list ->
            list.filter { it.startedAt.isAfter(cutoff) }
        }
    }

    private fun append(
        category: NetworkLogCategory,
        destination: String,
        startedAt: LocalDateTime,
        endedAt: LocalDateTime,
        result: String,
    ) {
        val entry = NetworkLogEntry(
            id = sequence.incrementAndGet(),
            category = category,
            destination = destination,
            startedAt = startedAt,
            endedAt = endedAt,
            result = result,
        )
        _entries.update { current ->
            listOf(entry) + current
        }
        prune(endedAt)
    }
}

fun networkDestination(url: String): String {
    return runCatching {
        val uri = URI(url)
        val host = uri.host ?: return@runCatching url
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isBlank() || path == "/") {
            host
        } else {
            "$host$path"
        }
    }.getOrDefault(url)
}

fun networkCategory(url: String): NetworkLogCategory {
    val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    val lower = url.lowercase()
    return when {
        host.contains("p2pquake") -> NetworkLogCategory.Earthquake
        host.contains("jma.go.jp") -> NetworkLogCategory.WeatherWarning
        host.contains("open-meteo") -> NetworkLogCategory.Weather
        host.contains("switch-bot.com") -> NetworkLogCategory.SwitchBot
        host == "api.spotify.com" || host == "accounts.spotify.com" ||
            host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com") -> NetworkLogCategory.Spotify
        host.contains("yahoo") && lower.contains("diainfo") -> NetworkLogCategory.Transit
        host.contains("yahoo") && lower.contains("timetable") -> NetworkLogCategory.Train
        host.contains("kinenbi.gr.jp") -> NetworkLogCategory.Memorial
        host.contains("doodlenote.net") -> NetworkLogCategory.Quote
        host.contains("expiration.takokake.com") -> NetworkLogCategory.Expiration
        host.contains("navitime") || host.contains("transfer-cloud") ||
            host.contains("transfer.navitime") || host.contains("limousinebus") ||
            host.contains("bushikaku") -> NetworkLogCategory.Bus
        else -> NetworkLogCategory.Bus
    }
}
