package app.smartdisplay.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * サウンドテストの再生。同時に鳴るのは1つで、別の音を選ぶとそちらへ切り替える。
 */
class SoundTestPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pausedForSpeech = false

    private val _trackId = MutableStateFlow<Int?>(null)
    val trackId: StateFlow<Int?> = _trackId.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun play(resId: Int, playbackVolume: Float) {
        releasePlayer()
        pausedForSpeech = false
        val created = MediaPlayer.create(appContext, resId) ?: return
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        created.setVolume(playbackVolume, playbackVolume)
        created.setOnCompletionListener { finished ->
            mainHandler.post {
                if (player !== finished) {
                    return@post
                }
                _playing.value = false
                pausedForSpeech = false
            }
        }
        created.setOnErrorListener { failed, _, _ ->
            mainHandler.post {
                if (player === failed) {
                    releasePlayer()
                }
            }
            true
        }
        player = created
        _trackId.value = resId
        _playing.value = true
        runCatching { created.start() }
    }

    fun togglePause() {
        val current = player ?: return
        pausedForSpeech = false
        runCatching {
            if (current.isPlaying) {
                current.pause()
                _playing.value = false
            } else {
                current.start()
                _playing.value = true
            }
        }
    }

    fun restart() {
        val current = player ?: return
        pausedForSpeech = false
        runCatching {
            current.seekTo(0)
            current.start()
            _playing.value = true
        }
    }

    /** 案内の読み上げ中だけ止めて、終わったら続きから戻す。 */
    fun setSpeechActive(active: Boolean) {
        val current = player ?: return
        if (active) {
            if (current.isPlaying) {
                runCatching { current.pause() }
                pausedForSpeech = true
                _playing.value = false
            }
            return
        }
        if (!pausedForSpeech) {
            return
        }
        pausedForSpeech = false
        runCatching { current.start() }
        _playing.value = true
    }

    fun stop() {
        releasePlayer()
    }

    private fun releasePlayer() {
        val current = player
        player = null
        pausedForSpeech = false
        _trackId.value = null
        _playing.value = false
        if (current == null) {
            return
        }
        runCatching { current.setOnCompletionListener(null) }
        runCatching { current.setOnErrorListener(null) }
        runCatching {
            if (current.isPlaying) {
                current.stop()
            }
        }
        runCatching { current.release() }
    }
}
