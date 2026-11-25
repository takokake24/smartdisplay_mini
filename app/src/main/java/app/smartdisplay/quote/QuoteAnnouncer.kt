package app.smartdisplay.quote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.smartdisplay.notification.NotificationCategoryQuote
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NetworkLog
import app.smartdisplay.util.NoInternetException
import app.smartdisplay.util.PreparedFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private const val QuoteUrl = "https://meigen.doodlenote.net/api/json.php?c=1"
private val TimeoutMs = TimeUnit.SECONDS.toMillis(12)

data class Quote(
    val text: String,
    val author: String,
)

/**
 * 12時の時報案内の最後に、名言を1件ランダムに読む。
 */
class QuoteAnnouncer(
    private val notifications: NotificationCenter,
) {
    private val prepared = PreparedFetch<QuotePrepared>()

    suspend fun onChimeFinished(time: LocalDateTime) {
        if (time.hour != 12 || time.minute != 0) {
            return
        }
        postAnnouncement(time.toLocalDate())
    }

    /** 時報の鳴り始めに呼ぶ。案内の時点では、ここで始めた取得を使う。 */
    fun prepare(day: LocalDate) {
        prepared.start(day) { loadPrepared() }
    }

    suspend fun postAnnouncement(day: LocalDate = LocalDate.now()) {
        val loaded = prepared.take(day)?.takeUnless { it is QuotePrepared.Offline }
            ?: loadPrepared()
        val quote = when (loaded) {
            QuotePrepared.Offline -> return
            QuotePrepared.Failed -> {
                notifications.postReadoutFailure(
                    title = "今日の格言",
                    message = "格言の取得に失敗しました。",
                )
                return
            }
            is QuotePrepared.Ready -> loaded.quote
        }
        notifications.post(
            category = NotificationCategoryQuote,
            title = "今日の格言",
            body = quote.text,
            speechText = quoteSpeech(quote),
            quoteAuthor = quote.author,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private suspend fun loadPrepared(): QuotePrepared {
        if (!InternetConnectivity.hasInternet()) {
            return QuotePrepared.Offline
        }
        return try {
            QuotePrepared.Ready(withContext(Dispatchers.IO) { fetchRandomQuote() })
        } catch (error: CancellationException) {
            throw error
        } catch (_: NoInternetException) {
            QuotePrepared.Offline
        } catch (_: Exception) {
            QuotePrepared.Failed
        }
    }
}

private sealed class QuotePrepared {
    data class Ready(val quote: Quote) : QuotePrepared()
    data object Offline : QuotePrepared()
    data object Failed : QuotePrepared()
}

fun quoteSpeech(quote: Quote): String {
    val author = quote.author.trim()
    return if (author.isEmpty()) {
        "「${quote.text}」。格言です。"
    } else {
        "「${quote.text}」。${author}の格言です。"
    }
}

private fun fetchRandomQuote(): Quote {
    return NetworkLog.trace(QuoteUrl) {
        val connection = (URL(QuoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TimeoutMs.toInt()
            readTimeout = TimeoutMs.toInt()
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "smartdisplay")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("格言 HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseQuote(body)
        } finally {
            connection.disconnect()
        }
    }
}

/** 作者フィールドは API の綴りどおり auther。 */
private fun parseQuote(body: String): Quote {
    val array = JSONArray(body.trim().removePrefix("\uFEFF"))
    if (array.length() == 0) {
        error("格言がありません")
    }
    val item = array.getJSONObject(0)
    val text = item.optString("meigen").trim()
    if (text.isEmpty()) {
        error("格言の本文がありません")
    }
    val author = item.optString("auther").ifBlank { item.optString("author") }.trim()
    return Quote(text = text, author = author)
}

@Composable
fun rememberQuoteAnnouncer(
    notifications: NotificationCenter,
): QuoteAnnouncer {
    return remember(notifications) {
        QuoteAnnouncer(notifications)
    }
}
