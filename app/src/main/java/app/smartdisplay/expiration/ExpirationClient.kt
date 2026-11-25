package app.smartdisplay.expiration

import app.smartdisplay.BuildConfig
import app.smartdisplay.util.NetworkLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

private const val ExpirationOrigin = "https://expiration.takokake.com/get/"
private val TimeoutMs = TimeUnit.SECONDS.toMillis(12)

class ExpirationAuthMissingException : IllegalStateException()

fun fetchAllExpirations(): List<ExpirationItem> = fetchExpirationItems("all.php")

fun fetchCloserExpirations(): List<ExpirationItem> = fetchExpirationItems("closer.php")

private fun fetchExpirationItems(script: String): List<ExpirationItem> {
    val login = BuildConfig.EXPIRATION_LOGIN_ID
    val password = BuildConfig.EXPIRATION_PASSWORD
    if (login.isBlank() || password.isBlank()) {
        throw ExpirationAuthMissingException()
    }
    val url = ExpirationOrigin + script +
        "?login_id=" + urlEncode(login) +
        "&password=" + urlEncode(password)
    return NetworkLog.trace(url) {
        try {
            downloadExpiration(url)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if ("password=" in message || "login_id=" in message) {
                throw IllegalStateException(error.javaClass.simpleName)
            }
            throw error
        }
    }
}

private fun urlEncode(value: String): String {
    return URLEncoder.encode(value, Charsets.UTF_8.name())
}

private fun downloadExpiration(url: String): List<ExpirationItem> {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TimeoutMs.toInt()
        readTimeout = TimeoutMs.toInt()
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "smartdisplay")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            error("賞味期限 HTTP $code")
        }
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return parseExpirations(body)
    } finally {
        connection.disconnect()
    }
}

private fun parseExpirations(body: String): List<ExpirationItem> {
    val root = JSONObject(body.trim().removePrefix("\uFEFF"))
    val data = root.optJSONArray("data") ?: error("賞味期限の形式が不正です")
    return buildList {
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val name = item.optString("product_name").trim().ifBlank { "名称未設定" }
            val dateText = item.optString("expiration_date").trim()
            val date = dateText.takeIf { it.isNotEmpty() }?.let { text ->
                runCatching { LocalDate.parse(text) }.getOrNull()
            }
            add(
                ExpirationItem(
                    id = item.optLong("id", index.toLong()),
                    name = name,
                    quantity = item.optInt("quantity", 1).coerceAtLeast(0),
                    expirationDate = date,
                    serverRemainingDays = item.optInt("remaining_days", 0),
                ),
            )
        }
    }
}
