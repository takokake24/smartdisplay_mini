package app.smartdisplay.spotify

enum class MusicOutput(val label: String, val spotifyName: String?) {
    ThisDevice("この端末", null),
    HomeGroup("ホームグループ", "ホームグループ"),
    NestHub("Nest Hub", "Nest Hub"),
    NestHub2("Nest Hub 2", "Nest Hub 2"),
    NestMini("Nest Mini", "Nest Mini"),
    ;

    companion object {
        fun fromStored(value: String?): MusicOutput {
            return entries.firstOrNull { it.name == value } ?: ThisDevice
        }
    }
}

enum class MusicPlaylist(val label: String) {
    SideA("A面"),
    SideB("B面"),
    ;

    companion object {
        fun fromStored(value: String?): MusicPlaylist? {
            return entries.firstOrNull { it.name == value }
        }
    }
}

data class SpotifyPlaylistItem(
    val name: String,
    val uri: String,
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val restricted: Boolean,
    val privateSession: Boolean,
) {
    val usable: Boolean
        get() = id.isNotBlank() && !restricted && !privateSession
}

data class MusicPlayback(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val trackUri: String?,
    val progressMs: Long,
    val contextUri: String?,
    val deviceId: String?,
    val deviceName: String?,
    val artworkUrl: String? = null,
    val shuffle: Boolean = false,
    val repeatAll: Boolean = false,
    val durationMs: Long = 0L,
)

/** 操作後の表示更新。取得できなかったときは画面を変えない。 */
internal sealed interface PlaybackRead {
    data class Value(val playback: MusicPlayback?) : PlaybackRead

    data object Unchanged : PlaybackRead
}
