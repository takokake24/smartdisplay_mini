package app.smartdisplay.spotify

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal class SpotifyClient(private val context: Context) {
    fun devices(): List<SpotifyDevice> {
        return authorized { token ->
            val json = SpotifyHttp.json(
                url = DevicesUrl,
                method = "GET",
                token = token,
                body = null,
                contentType = null,
                fallback = "再生先を取得できませんでした",
                retryIo = true,
            ) ?: throw IllegalStateException("再生先を取得できませんでした")
            parseDevices(json)
        }
    }

    fun artwork(url: String): ByteArray = SpotifyHttp.image(url)

    fun playback(): MusicPlayback? {
        return authorized { token ->
            val json = SpotifyHttp.json(
                url = PlayerUrl,
                method = "GET",
                token = token,
                body = null,
                contentType = null,
                fallback = "再生状況を取得できませんでした",
                retryIo = true,
            ) ?: return@authorized null
            parsePlayback(json)
        }
    }

    fun playlistUri(playlist: MusicPlaylist): String {
        SpotifySession.cachedPlaylistUri(context, playlist)?.let { return it }
        val uri = authorized { token ->
            findPlaylist(token, playlist.label)
        }
        SpotifySession.savePlaylistUri(context, playlist, uri)
        return uri
    }

    fun playContext(deviceId: String, contextUri: String) {
        authorized { token ->
            SpotifyHttp.exchange(
                url = playerUrl("play", deviceId),
                method = "PUT",
                token = token,
                body = JSONObject().put("context_uri", contextUri).toString(),
                contentType = JsonType,
                fallback = PlayFailure,
                retryIo = false,
            )
        }
    }

    fun resume(deviceId: String) {
        authorized { token ->
            SpotifyHttp.exchange(
                url = playerUrl("play", deviceId),
                method = "PUT",
                token = token,
                body = "{}",
                contentType = JsonType,
                fallback = PlayFailure,
                retryIo = false,
            )
        }
    }

    fun transfer(deviceId: String, play: Boolean = false) {
        authorized { token ->
            SpotifyHttp.exchange(
                url = PlayerUrl,
                method = "PUT",
                token = token,
                body = JSONObject()
                    .put("device_ids", JSONArray().put(deviceId))
                    .put("play", play)
                    .toString(),
                contentType = JsonType,
                fallback = PlayFailure,
                retryIo = false,
            )
        }
    }

    fun pause(deviceId: String) {
        send("pause", "PUT", deviceId, "一時停止できませんでした")
    }

    fun next(deviceId: String) {
        send("next", "POST", deviceId, "曲を切り替えられませんでした")
    }

    fun previous(deviceId: String) {
        send("previous", "POST", deviceId, "曲を切り替えられませんでした")
    }

    fun shuffle(deviceId: String, enabled: Boolean) {
        sendMode("shuffle", if (enabled) "true" else "false", deviceId, "シャッフルを切り替えられませんでした")
    }

    fun repeatAll(deviceId: String, enabled: Boolean) {
        sendMode("repeat", if (enabled) "context" else "off", deviceId, "ループを切り替えられませんでした")
    }

    private fun send(action: String, method: String, deviceId: String, fallback: String) {
        authorized { token ->
            SpotifyHttp.exchange(
                url = playerUrl(action, deviceId),
                method = method,
                token = token,
                body = null,
                contentType = null,
                fallback = fallback,
                retryIo = false,
            )
        }
    }

    private fun <T> authorized(block: (String) -> T): T {
        return try {
            block(SpotifySession.accessToken(context))
        } catch (_: SpotifyUnauthorized) {
            try {
                block(SpotifySession.forceRefresh(context))
            } catch (_: SpotifyUnauthorized) {
                SpotifySession.clear(context)
                throw SpotifyReconnectException()
            }
        }
    }

    private fun findPlaylist(token: String, name: String): String {
        var url: String? = PlaylistsUrl
        while (!url.isNullOrBlank()) {
            val json = SpotifyHttp.json(
                url = url,
                method = "GET",
                token = token,
                body = null,
                contentType = null,
                fallback = "プレイリストを取得できませんでした",
                retryIo = true,
            ) ?: break
            val items = json.optJSONArray("items")
            if (items != null) {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optString("name") != name) {
                        continue
                    }
                    val uri = item.optString("uri").trim()
                    if (uri.isNotEmpty()) {
                        return uri
                    }
                }
            }
            url = if (json.isNull("next")) {
                null
            } else {
                json.optString("next").takeIf { it.isNotBlank() }
            }
        }
        throw IllegalStateException("プレイリスト「$name」が見つかりません")
    }

    fun playlists(): List<SpotifyPlaylistItem> {
        return authorized { token ->
            val found = mutableListOf<SpotifyPlaylistItem>()
            var url: String? = PlaylistsUrl
            while (!url.isNullOrBlank()) {
                val json = SpotifyHttp.json(
                    url = url,
                    method = "GET",
                    token = token,
                    body = null,
                    contentType = null,
                    fallback = "プレイリストを取得できませんでした",
                    retryIo = true,
                ) ?: break
                val items = json.optJSONArray("items")
                if (items != null) {
                    for (index in 0 until items.length()) {
                        if (items.isNull(index)) {
                            continue
                        }
                        val item = items.optJSONObject(index) ?: continue
                        val name = item.optString("name").trim()
                        val uri = item.optString("uri").trim()
                        if (name.isEmpty() || uri.isEmpty()) {
                            continue
                        }
                        found += SpotifyPlaylistItem(name = name, uri = uri)
                    }
                }
                url = if (json.isNull("next")) {
                    null
                } else {
                    json.optString("next").takeIf { it.isNotBlank() }
                }
            }
            found
        }
    }

    private fun sendMode(action: String, state: String, deviceId: String, fallback: String) {
        authorized { token ->
            SpotifyHttp.exchange(
                url = "$PlayerUrl/$action?state=${Uri.encode(state)}&device_id=${Uri.encode(deviceId)}",
                method = "PUT",
                token = token,
                body = null,
                contentType = null,
                fallback = fallback,
                retryIo = false,
            )
        }
    }

    private fun playerUrl(action: String, deviceId: String): String {
        return "$PlayerUrl/$action?device_id=${Uri.encode(deviceId)}"
    }

    private fun parseDevices(json: JSONObject): List<SpotifyDevice> {
        val list = json.optJSONArray("devices") ?: return emptyList()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                if (item.isNull("id")) {
                    continue
                }
                val id = item.optString("id").trim()
                if (id.isEmpty()) {
                    continue
                }
                add(
                    SpotifyDevice(
                        id = id,
                        name = item.optString("name").trim(),
                        type = item.optString("type").trim(),
                        active = item.optBoolean("is_active"),
                        restricted = item.optBoolean("is_restricted"),
                        privateSession = item.optBoolean("is_private_session"),
                    ),
                )
            }
        }
    }

    private fun parsePlayback(json: JSONObject): MusicPlayback {
        val item = if (json.isNull("item")) null else json.optJSONObject("item")
        val context = if (json.isNull("context")) null else json.optJSONObject("context")
        val device = if (json.isNull("device")) null else json.optJSONObject("device")
        val deviceId = if (device == null || device.isNull("id")) {
            null
        } else {
            device.optString("id").trim().takeIf { it.isNotEmpty() }
        }
        return MusicPlayback(
            title = item?.optString("name")?.trim().orEmpty(),
            artist = artistName(item),
            playing = json.optBoolean("is_playing"),
            trackUri = item?.optString("uri")?.trim()?.takeIf { it.isNotEmpty() },
            progressMs = if (json.isNull("progress_ms")) -1L else json.optLong("progress_ms"),
            contextUri = context?.optString("uri")?.trim()?.takeIf { it.isNotEmpty() },
            deviceId = deviceId,
            deviceName = device?.optString("name")?.trim()?.takeIf { it.isNotEmpty() },
            artworkUrl = artworkUrl(item),
            shuffle = json.optBoolean("shuffle_state"),
            repeatAll = json.optString("repeat_state") == "context",
            durationMs = item?.optLong("duration_ms")?.takeIf { it > 0L } ?: 0L,
        )
    }

    private fun artworkUrl(item: JSONObject?): String? {
        if (item == null) {
            return null
        }
        val images = item.optJSONObject("album")?.optJSONArray("images")
            ?: item.optJSONArray("images")
            ?: item.optJSONObject("show")?.optJSONArray("images")
        return pickArtwork(images)
    }

    private fun pickArtwork(images: JSONArray?): String? {
        if (images == null) {
            return null
        }
        var modest: String? = null
        var modestWidth = Int.MAX_VALUE
        var any: String? = null
        var anyWidth = -1
        for (index in 0 until images.length()) {
            val image = images.optJSONObject(index) ?: continue
            val url = image.optString("url").trim()
            if (!url.startsWith("https://")) {
                continue
            }
            val width = image.optInt("width")
            if (width >= anyWidth) {
                any = url
                anyWidth = width
            }
            if (width in 160..640 && width < modestWidth) {
                modest = url
                modestWidth = width
            }
        }
        return modest ?: any
    }

    private fun artistName(item: JSONObject?): String {
        if (item == null) {
            return ""
        }
        val artists = item.optJSONArray("artists")
        if (artists != null) {
            val names = buildList {
                for (index in 0 until artists.length()) {
                    val name = artists.optJSONObject(index)?.optString("name")?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        add(name)
                    }
                }
            }
            if (names.isNotEmpty()) {
                return names.joinToString("、")
            }
        }
        return item.optJSONObject("show")?.optString("name")?.trim().orEmpty()
    }

    private companion object {
        private const val ApiRoot = "https://api.spotify.com/v1"
        private const val DevicesUrl = "$ApiRoot/me/player/devices"
        private const val PlayerUrl = "$ApiRoot/me/player"
        private const val PlaylistsUrl = "$ApiRoot/me/playlists?limit=50"
        private const val JsonType = "application/json"
    }
}
