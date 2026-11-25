package app.smartdisplay.battery

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * OS の充電開始音。鳴るときはアプリ側の SE を出さず、その長さだけ読み上げを遅らせる。
 */
internal object ChargingSounds {
    private val durationCache = HashMap<String, Long>()

    fun osChargingSoundEnabled(context: Context): Boolean {
        val resolver = context.contentResolver
        val enabled = runCatching {
            Settings.Global.getInt(resolver, ChargingSoundsEnabledKey, 1)
        }.getOrDefault(1) == 1
        if (!enabled) {
            return false
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT,
            AudioManager.RINGER_MODE_VIBRATE,
            -> return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            audio.isStreamMute(AudioManager.STREAM_SYSTEM)
        ) {
            return false
        }
        return audio.getStreamVolume(AudioManager.STREAM_SYSTEM) > 0
    }

    fun waitMillis(context: Context, wireless: Boolean): Long {
        val path = resolveSoundPath(wireless)
        val measured = path?.let { durationMillis(it) }
        val base = (measured ?: FallbackSoundMillis).coerceIn(1L, MaxSoundMillis)
        return base + SoundCushionMillis
    }

    private fun resolveSoundPath(wireless: Boolean): String? {
        val names = if (wireless) {
            listOf("WirelessChargingStarted.ogg", "ChargingStarted.ogg")
        } else {
            listOf("ChargingStarted.ogg", "WirelessChargingStarted.ogg")
        }
        for (root in SoundRoots) {
            for (name in names) {
                val file = File(root, name)
                if (file.isFile) {
                    return file.absolutePath
                }
            }
        }
        return null
    }

    private fun durationMillis(path: String): Long? {
        synchronized(durationCache) {
            durationCache[path]?.let { return it }
        }
        val measured = readDuration(path) ?: return null
        synchronized(durationCache) {
            durationCache[path] = measured
        }
        return measured
    }

    private fun readDuration(path: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private const val ChargingSoundsEnabledKey = "charging_sounds_enabled"
    private const val FallbackSoundMillis = 1_500L
    private const val MaxSoundMillis = 5_000L
    private const val SoundCushionMillis = 350L

    private val SoundRoots = listOf(
        "/system/media/audio/ui",
        "/product/media/audio/ui",
        "/system/product/media/audio/ui",
        "/odm/media/audio/ui",
    )
}
