package app.smartdisplay.spotify

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import app.smartdisplay.BuildConfig
import app.smartdisplay.util.showOsAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal const val SpotifyRedirectUri = "smartdisplay://spotify-callback"

internal val spotifyClientId: String = BuildConfig.SPOTIFY_CLIENT_ID

internal val spotifyConfigured: Boolean = spotifyClientId.isNotBlank()

/**
 * Spotify の認可コード（PKCE）と再生先の選択。
 * リダイレクト URI は AndroidManifest の intent-filter と揃える。
 */
internal object SpotifySession {
    private const val PrefsName = "spotify"
    private const val KeyRefresh = "refresh_token"
    private const val KeyAccess = "access_token"
    private const val KeyExpiresAt = "access_expires_at"
    private const val KeyVerifier = "code_verifier"
    private const val KeyState = "auth_state"
    private const val KeyOutput = "output"
    private const val KeyPlaylist = "playlist"
    private const val KeyShuffle = "shuffle_on"
    private const val KeyRepeatAll = "repeat_all"
    private const val KeyLocalDevice = "local_device_id"
    private const val TokenUrl = "https://accounts.spotify.com/api/token"
    private const val AuthorizeUrl = "https://accounts.spotify.com/authorize"
    private const val Scopes =
        "user-read-playback-state user-modify-playback-state playlist-read-private"

    private val lock = Any()
    private val random = SecureRandom()
    private val linkingGate = AtomicBoolean(false)
    private val _connected = MutableStateFlow(false)
    private val _linking = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    val linking: StateFlow<Boolean> = _linking.asStateFlow()

    fun attach(context: Context) {
        synchronized(lock) {
            _connected.value = refreshToken(context).isNotBlank()
        }
    }

    fun beginLogin(context: Context) {
        if (!spotifyConfigured) {
            throw IllegalStateException("認証情報がありません")
        }
        val verifier = newVerifier()
        val state = UUID.randomUUID().toString()
        synchronized(lock) {
            prefs(context).edit()
                .putString(KeyVerifier, verifier)
                .putString(KeyState, state)
                .commit()
        }
        val uri = Uri.parse(AuthorizeUrl).buildUpon()
            .appendQueryParameter("client_id", spotifyClientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyRedirectUri)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", Scopes)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", codeChallenge(verifier))
            .build()
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun completeLogin(context: Context, uri: Uri) {
        if (!linkingGate.compareAndSet(false, true)) {
            return
        }
        _linking.value = true
        val app = context.applicationContext
        Thread {
            try {
                runCatching { completeLoginBlocking(app, uri) }
                    .onFailure { error ->
                        showOsAlert(error.message ?: "Spotifyに接続できませんでした")
                    }
            } finally {
                _linking.value = false
                linkingGate.set(false)
            }
        }.start()
    }

    fun disconnect(context: Context) {
        synchronized(lock) {
            val editor = prefs(context).edit()
            editor.remove(KeyRefresh)
            editor.remove(KeyAccess)
            editor.remove(KeyExpiresAt)
            editor.remove(KeyVerifier)
            editor.remove(KeyState)
            editor.remove(KeyLocalDevice)
            MusicPlaylist.entries.forEach { playlist ->
                editor.remove(playlistKey(playlist))
            }
            editor.commit()
            _connected.value = false
        }
    }

    fun accessToken(context: Context): String = synchronized(lock) {
        validAccess(context) ?: refreshLocked(context)
    }

    fun forceRefresh(context: Context): String = synchronized(lock) {
        refreshLocked(context)
    }

    fun clear(context: Context) {
        disconnect(context)
    }

    fun output(context: Context): MusicOutput {
        return MusicOutput.fromStored(prefs(context).getString(KeyOutput, null))
    }

    fun saveOutput(context: Context, output: MusicOutput) {
        prefs(context).edit().putString(KeyOutput, output.name).apply()
    }

    fun playlist(context: Context): MusicPlaylist? {
        return MusicPlaylist.fromStored(prefs(context).getString(KeyPlaylist, null))
    }

    fun savePlaylist(context: Context, playlist: MusicPlaylist) {
        prefs(context).edit().putString(KeyPlaylist, playlist.name).apply()
    }

    fun shuffleOn(context: Context): Boolean {
        val stored = prefs(context)
        if (!stored.contains(KeyShuffle)) {
            return true
        }
        return stored.getBoolean(KeyShuffle, true)
    }

    fun saveShuffleOn(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KeyShuffle, enabled).apply()
    }

    fun repeatAll(context: Context): Boolean {
        val stored = prefs(context)
        if (!stored.contains(KeyRepeatAll)) {
            return true
        }
        return stored.getBoolean(KeyRepeatAll, true)
    }

    fun saveRepeatAll(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KeyRepeatAll, enabled).apply()
    }

    fun localDeviceId(context: Context): String? {
        return prefs(context).getString(KeyLocalDevice, null)?.takeIf { it.isNotBlank() }
    }

    fun saveLocalDeviceId(context: Context, id: String) {
        if (id.isBlank()) {
            return
        }
        prefs(context).edit().putString(KeyLocalDevice, id).apply()
    }

    fun cachedPlaylistUri(context: Context, playlist: MusicPlaylist): String? {
        return prefs(context).getString(playlistKey(playlist), null)?.takeIf { it.isNotBlank() }
    }

    fun savePlaylistUri(context: Context, playlist: MusicPlaylist, uri: String) {
        if (uri.isBlank()) {
            return
        }
        prefs(context).edit().putString(playlistKey(playlist), uri).apply()
    }

    private fun completeLoginBlocking(context: Context, uri: Uri) {
        val failure = uri.getQueryParameter("error")
        if (!failure.isNullOrBlank()) {
            clearPending(context)
            if (failure == "access_denied") {
                throw IllegalStateException("Spotifyへの接続がキャンセルされました")
            }
            throw IllegalStateException("Spotifyに接続できませんでした")
        }
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        val verifier = synchronized(lock) { prefs(context).getString(KeyVerifier, null).orEmpty() }
        val expected = synchronized(lock) { prefs(context).getString(KeyState, null).orEmpty() }
        clearPending(context)
        if (code.isBlank() || state.isBlank() || verifier.isBlank() || state != expected) {
            throw IllegalStateException("Spotifyに接続できませんでした")
        }
        val tokens = try {
            requestToken(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to SpotifyRedirectUri,
                    "client_id" to spotifyClientId,
                    "code_verifier" to verifier,
                ),
            )
        } catch (_: SpotifyReconnectException) {
            throw IllegalStateException("Spotifyに接続できませんでした")
        }
        synchronized(lock) {
            storeTokens(context, tokens, previousRefresh = null)
            _connected.value = true
        }
    }

    private fun refreshLocked(context: Context): String {
        val refresh = refreshToken(context)
        if (refresh.isBlank()) {
            _connected.value = false
            throw IllegalStateException("Spotifyに接続してください")
        }
        try {
            val tokens = requestToken(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refresh,
                    "client_id" to spotifyClientId,
                ),
            )
            storeTokens(context, tokens, previousRefresh = refresh)
            _connected.value = true
            return tokens.accessToken
        } catch (error: SpotifyReconnectException) {
            disconnect(context)
            throw error
        }
    }

    private fun requestToken(fields: Map<String, String>): SpotifyTokens {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${formEncode(key)}=${formEncode(value)}"
        }
        val result = SpotifyHttp.exchange(
            url = TokenUrl,
            method = "POST",
            token = null,
            body = body,
            contentType = "application/x-www-form-urlencoded",
            fallback = "Spotifyに接続できませんでした",
            retryIo = true,
        )
        val json = try {
            JSONObject(result.text)
        } catch (_: Exception) {
            throw IllegalStateException("Spotifyに接続できませんでした")
        }
        val access = json.optString("access_token").trim()
        if (access.isBlank()) {
            throw IllegalStateException("Spotifyに接続できませんでした")
        }
        val refresh = json.optString("refresh_token").trim()
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(120L)
        return SpotifyTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = System.currentTimeMillis() + expiresIn * 1000L - 60_000L,
        )
    }

    private fun storeTokens(context: Context, tokens: SpotifyTokens, previousRefresh: String?) {
        val refresh = tokens.refreshToken.ifBlank { previousRefresh.orEmpty() }
        prefs(context).edit()
            .putString(KeyAccess, tokens.accessToken)
            .putString(KeyRefresh, refresh)
            .putLong(KeyExpiresAt, tokens.expiresAt)
            .remove(KeyVerifier)
            .remove(KeyState)
            .commit()
    }

    private fun validAccess(context: Context): String? {
        val access = prefs(context).getString(KeyAccess, null).orEmpty()
        val expiresAt = prefs(context).getLong(KeyExpiresAt, 0L)
        if (access.isBlank() || System.currentTimeMillis() >= expiresAt) {
            return null
        }
        return access
    }

    private fun refreshToken(context: Context): String {
        return prefs(context).getString(KeyRefresh, null).orEmpty()
    }

    private fun clearPending(context: Context) {
        synchronized(lock) {
            prefs(context).edit()
                .remove(KeyVerifier)
                .remove(KeyState)
                .commit()
        }
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    }

    private fun playlistKey(playlist: MusicPlaylist): String {
        return "playlist_uri_${playlist.name}"
    }

    private fun newVerifier(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    @Suppress("DEPRECATION")
    private fun formEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }
}

private data class SpotifyTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)
