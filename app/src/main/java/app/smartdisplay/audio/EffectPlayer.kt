package app.smartdisplay.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EffectPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var tapId = 0
    private var tapLoaded = false
    private var beepId = 0
    private var beepLoaded = false
    private var activeStream = 0
    private var activePlayer: MediaPlayer? = null
    private var activeAwait: CompletableDeferred<Unit>? = null

    /** 外出時の鳴動抑制。未設定なら常に鳴らす。 */
    @Volatile
    var presenceGate: SoundPresenceGate? = null

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) {
                return@setOnLoadCompleteListener
            }
            when (sampleId) {
                tapId -> tapLoaded = true
                beepId -> beepLoaded = true
            }
        }
        tapId = soundPool.load(appContext, R.raw.tap, 1)
        beepId = soundPool.load(appContext, R.raw.beep, 1)
    }

    private fun mayPlay(): Boolean = presenceGate?.mayPlayNow() != false

    fun playTap() {
        if (!mayPlay() || !tapLoaded || tapId == 0) {
            return
        }
        playExclusive(tapId)
    }

    fun playBeep() {
        if (!mayPlay() || !beepLoaded || beepId == 0) {
            return
        }
        playExclusive(beepId)
    }

    suspend fun playBeepAndAwait() {
        if (!mayPlay()) {
            return
        }
        playRawAndAwait(R.raw.beep)
    }

    /**
     * 同じ効果音を重ねるとミキサーが音量を落とす。
     * 再生中のものを止めてから、常に最大音量で鳴らし直す。
     */
    private fun playExclusive(soundId: Int) {
        if (activeStream != 0) {
            soundPool.stop(activeStream)
            activeStream = 0
        }
        val stream = soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        if (stream == 0) {
            return
        }
        soundPool.setVolume(stream, 1f, 1f)
        activeStream = stream
    }

    suspend fun playNotice() {
        if (!mayPlay()) {
            return
        }
        playRawAndAwait(R.raw.notice)
    }

    suspend fun playBattery() {
        if (!mayPlay()) {
            return
        }
        playRawAndAwait(R.raw.battery)
    }

    private suspend fun playRawAndAwait(resId: Int) {
        mutex.withLock {
            withContext(Dispatchers.Main) {
                if (!mayPlay()) {
                    return@withContext
                }
                if (activeStream != 0) {
                    soundPool.stop(activeStream)
                    activeStream = 0
                }
                val done = CompletableDeferred<Unit>()
                val player = MediaPlayer.create(appContext, resId) ?: return@withContext
                activeAwait = done
                activePlayer = player
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                player.setVolume(1f, 1f)
                player.setOnCompletionListener {
                    done.complete(Unit)
                }
                player.setOnErrorListener { _, _, _ ->
                    done.complete(Unit)
                    true
                }
                try {
                    player.start()
                    done.await()
                } finally {
                    if (activeAwait === done) {
                        activeAwait = null
                    }
                    if (activePlayer === player) {
                        activePlayer = null
                    }
                    runCatching {
                        player.stop()
                        player.release()
                    }
                }
            }
        }
    }

    /** イヤホン切断や外出抑制で、再生中の効果音を止める。 */
    fun stop() {
        if (activeStream != 0) {
            soundPool.stop(activeStream)
            activeStream = 0
        }
        activeAwait?.complete(Unit)
        activeAwait = null
        val player = activePlayer
        activePlayer = null
        if (player != null) {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    fun release() {
        stop()
        soundPool.release()
    }
}

@Composable
fun rememberEffectPlayer(): EffectPlayer {
    val context = LocalContext.current
    val player = remember { EffectPlayer(context.applicationContext) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}
