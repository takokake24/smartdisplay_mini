package app.smartdisplay.away

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.smartdisplay.R
import java.util.ArrayDeque

/**
 * 外出アラート中の BGM。
 * `res/raw/away_alert_` で始まる音源をシャッフルして、終わったら次を鳴らす。
 * 一度流した曲は、残りの曲が全部終わるまで繰り返さない。
 */
class AwayAlertMusic(context: Context) {
    private val appContext = context.applicationContext
    private val tracks: List<Int> = awayAlertTrackResIds()
    private val order = ArrayDeque<Int>()
    private var player: MediaPlayer? = null
    private var running = false
    private var ducked = false

    fun start() {
        if (tracks.isEmpty()) {
            return
        }
        running = true
        if (player == null) {
            playNext()
        } else {
            resume()
        }
    }

    fun pause() {
        val current = player ?: return
        runCatching {
            if (current.isPlaying) {
                current.pause()
            }
        }
    }

    fun resume() {
        if (!running) {
            return
        }
        val current = player
        if (current == null) {
            playNext()
            return
        }
        runCatching {
            if (!current.isPlaying) {
                current.start()
            }
        }
    }

    /** 案内のあいだだけ曲を下げる。案内そのものの音量は変えない。 */
    fun setDucked(value: Boolean) {
        ducked = value
        applyVolume()
    }

    fun stop() {
        running = false
        ducked = false
        order.clear()
        releasePlayer()
    }

    private fun playNext() {
        if (!running) {
            return
        }
        val resId = nextTrack() ?: return
        releasePlayer()
        val created = MediaPlayer.create(appContext, resId) ?: return
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        created.setOnCompletionListener { finished ->
            finished.setOnCompletionListener(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!running || player !== finished) {
                    return@post
                }
                releasePlayer()
                playNext()
            }
        }
        created.setOnErrorListener { failed, _, _ ->
            failed.setOnErrorListener(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (player === failed) {
                    releasePlayer()
                }
            }
            true
        }
        player = created
        applyVolume()
        runCatching { created.start() }
    }

    private fun nextTrack(): Int? = order.takeShuffled(tracks)

    private fun applyVolume() {
        val volume = if (ducked) DuckVolume else 1f
        player?.setVolume(volume, volume)
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        current.releaseQuietly()
    }

    private companion object {
        const val DuckVolume = 0.22f
    }
}

internal fun awayAlertTrackResIds(): List<Int> =
    R.raw::class.java.fields.mapNotNull { field ->
        if (field.name.startsWith("away_alert_")) {
            runCatching { field.getInt(null) }.getOrNull()
        } else {
            null
        }
    }

internal fun ArrayDeque<Int>.takeShuffled(source: List<Int>): Int? {
    if (source.isEmpty()) {
        return null
    }
    if (isEmpty()) {
        addAll(source.shuffled())
    }
    return removeFirst()
}

internal fun MediaPlayer.releaseQuietly() {
    runCatching { setOnCompletionListener(null) }
    runCatching { setOnErrorListener(null) }
    runCatching {
        if (isPlaying) {
            stop()
        }
    }
    runCatching { release() }
}
