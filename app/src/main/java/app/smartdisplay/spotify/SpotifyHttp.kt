package app.smartdisplay.spotify

import app.smartdisplay.util.NetworkLog
import app.smartdisplay.util.NoInternetException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit

internal class SpotifyUnauthorized : Exception("認証の有効期限が切れました")

internal class SpotifyReconnectException : IllegalStateException("Spotifyに接続し直してください")

internal class SpotifyDeviceNotFound : IllegalStateException("再生先が見つかりません")

internal data class SpotifyHttpResult(val code: Int, val text: String)

/**
 * Spotify Web API / Accounts への HTTP。
 * 操作系は再送しない。取得系だけ、切れていない通信失敗を数回やり直す。
 */
internal object SpotifyHttp {
    private val TimeoutMs = TimeUnit.SECONDS.toMillis(12).toInt()
    private const val MaxImageBytes = 1_048_576
    private const val MaxImageRedirects = 4

    fun exchange(
        url: String,
        method: String,
        token: String?,
        body: String?,
        contentType: String?,
        fallback: String,
        retryIo: Boolean,
    ): SpotifyHttpResult {
        var last: IOException? = null
        val attempts = if (retryIo) 3 else 1
        repeat(attempts) { attempt ->
            try {
                return NetworkLog.trace(url, retry = false) {
                    val result = open(url, method, token, body, contentType)
                    if (result.code == 401 && token != null) {
                        throw SpotifyUnauthorized()
                    }
                    if (result.code !in 200..299) {
                        throw failure(result.code, result.text, fallback)
                    }
                    result
                }
            } catch (error: SpotifyUnauthorized) {
                throw error
            } catch (error: SpotifyReconnectException) {
                throw error
            } catch (error: SpotifyDeviceNotFound) {
                throw error
            } catch (error: NoInternetException) {
                throw error
            } catch (error: IOException) {
                last = error
                if (attempt == attempts - 1) {
                    throw error
                }
                sleepBeforeRetry(attempt)
            }
        }
        throw last ?: IllegalStateException(fallback)
    }

    fun json(
        url: String,
        method: String,
        token: String?,
        body: String?,
        contentType: String?,
        fallback: String,
        retryIo: Boolean,
    ): JSONObject? {
        val result = exchange(url, method, token, body, contentType, fallback, retryIo)
        if (result.text.isBlank()) {
            return null
        }
        return JSONObject(result.text)
    }

    fun image(url: String): ByteArray {
        ensureSpotifyImage(url)
        return NetworkLog.trace(url) {
            var current = url
            var body: ByteArray? = null
            var redirects = 0
            while (body == null && redirects < MaxImageRedirects) {
                ensureSpotifyImage(current)
                val connection = URL(current).openConnection() as HttpURLConnection
                connection.connectTimeout = TimeoutMs
                connection.readTimeout = TimeoutMs
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "image/*")
                connection.setRequestProperty("User-Agent", "smartdisplay")
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("画像を取得できませんでした")
                        current = URL(URL(current), location).toString()
                        redirects += 1
                    } else if (code !in 200..299) {
                        throw IOException("画像を取得できませんでした")
                    } else {
                        body = readLimited(connection)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            body ?: throw IOException("画像を取得できませんでした")
        }
    }

    private fun open(
        url: String,
        method: String,
        token: String?,
        body: String?,
        contentType: String?,
    ): SpotifyHttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TimeoutMs
            connection.readTimeout = TimeoutMs
            connection.requestMethod = method
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "smartdisplay")
            if (!token.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                if (!contentType.isNullOrBlank()) {
                    connection.setRequestProperty("Content-Type", contentType)
                }
                connection.outputStream.use { stream ->
                    stream.write(bytes)
                }
            } else if (method == "POST" || method == "PUT") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return SpotifyHttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun failure(code: Int, body: String, fallback: String): Exception {
        val api = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val grant = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
        if (grant == "invalid_grant") {
            return SpotifyReconnectException()
        }
        val text = when {
            code == 429 -> "しばらくしてからもう一度操作してください"
            code == 403 && api.contains("Premium", ignoreCase = true) -> "Spotify Premium が必要です"
            code == 404 && fallback == PlayFailure -> "再生先が見つかりません"
            code == 404 -> "再生中の曲がありません"
            code == 403 -> "この再生先は操作できません"
            else -> fallback
        }
        if (text == "再生先が見つかりません") {
            return SpotifyDeviceNotFound()
        }
        return IllegalStateException(text)
    }

    private fun readLimited(connection: HttpURLConnection): ByteArray {
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) {
                    break
                }
                total += count
                if (total > MaxImageBytes) {
                    throw IOException("画像を取得できませんでした")
                }
                output.write(chunk, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun ensureSpotifyImage(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val allowed = uri?.scheme == "https" &&
            (host.endsWith(".scdn.co") || host.endsWith(".spotifycdn.com"))
        if (!allowed) {
            throw IOException("画像を取得できませんでした")
        }
    }

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            Thread.sleep(800L * (attempt + 1))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }
}

internal const val PlayFailure = "再生できませんでした"
