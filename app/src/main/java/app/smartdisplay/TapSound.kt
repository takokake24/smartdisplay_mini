package app.smartdisplay

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberTapSound(): () -> Unit {
    val context = LocalContext.current
    val player = remember { TapSoundPlayer(context.applicationContext) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player::play
}

private class TapSoundPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private var soundId = 0
    private var loaded = false

    init {
        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) {
                loaded = true
            }
        }
        soundId = soundPool.load(context, R.raw.tap, 1)
    }

    fun play() {
        if (!loaded || soundId == 0) {
            return
        }
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        soundPool.release()
    }
}
