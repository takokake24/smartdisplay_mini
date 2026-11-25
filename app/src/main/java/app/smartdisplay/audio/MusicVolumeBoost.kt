package app.smartdisplay.audio

import android.content.Context
import android.media.AudioManager

/**
 * メディア音量の一時的な引き上げ。
 * 試聴や警報が重なっても、先に終わった側が音量を戻して後から鳴った音を小さくしない。
 */
object MusicVolumeBoost {
    private val lock = Any()
    private val ratios = mutableListOf<Float>()
    private var savedVolume: Int = 0
    private var savedMuted: Boolean = false

    fun raise(context: Context, ratio: Float) {
        val audio = audioManager(context) ?: return
        val level = ratio.coerceIn(0f, 1f)
        synchronized(lock) {
            if (ratios.isEmpty()) {
                savedMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            ratios.add(level)
            apply(audio, level)
        }
    }

    /** 引き上げ中に音量が変わっていたら、いまの引き上げ先へ戻す。 */
    fun reapply(context: Context) {
        val audio = audioManager(context) ?: return
        synchronized(lock) {
            val ratio = ratios.lastOrNull() ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val target = (max * ratio).toInt().coerceIn(0, max)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            if (current == target && !(muted && target > 0)) {
                return
            }
            apply(audio, ratio)
        }
    }

    fun restore(context: Context) {
        val audio = audioManager(context) ?: return
        synchronized(lock) {
            if (ratios.isEmpty()) {
                return
            }
            ratios.removeAt(ratios.lastIndex)
            if (ratios.isEmpty()) {
                audio.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    savedVolume.coerceAtLeast(0),
                    0,
                )
                if (savedMuted) {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        0,
                    )
                }
            } else {
                apply(audio, ratios.last())
            }
        }
    }

    private fun apply(audio: AudioManager, ratio: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (max * ratio).toInt().coerceIn(0, max)
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) && target > 0) {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_UNMUTE,
                0,
            )
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    private fun audioManager(context: Context): AudioManager? {
        return context.applicationContext.getSystemService(AudioManager::class.java)
    }
}
