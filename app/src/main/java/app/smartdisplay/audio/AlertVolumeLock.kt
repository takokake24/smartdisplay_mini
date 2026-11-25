package app.smartdisplay.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 外出アラート中は音量ボタンを捨て、メディア音量が変わっても引き上げ先へ戻す。
 */
object VolumeKeyGuard {
    @Volatile
    var locked: Boolean = false

    fun isVolumeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
    }
}

@Composable
fun AwayAlertVolumeLock(active: Boolean) {
    val context = LocalContext.current
    DisposableEffect(active) {
        if (!active) {
            onDispose { }
        } else {
            VolumeKeyGuard.locked = true
            val appContext = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val stream = intent.getIntExtra(ExtraVolumeStreamType, -1)
                    if (stream != AudioManager.STREAM_MUSIC) {
                        return
                    }
                    MusicVolumeBoost.reapply(appContext)
                }
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(VolumeChangedAction),
                ContextCompat.RECEIVER_EXPORTED,
            )
            onDispose {
                VolumeKeyGuard.locked = false
                runCatching { appContext.unregisterReceiver(receiver) }
            }
        }
    }
}

private const val VolumeChangedAction = "android.media.VOLUME_CHANGED_ACTION"
private const val ExtraVolumeStreamType = "android.media.EXTRA_VOLUME_STREAM_TYPE"
