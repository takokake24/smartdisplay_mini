package app.smartdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.smartdisplay.util.showOsAlert

/**
 * 電源供給の開始と喪失を、画面下中央のトーストで知らせる。音は鳴らさない。
 * 起動時にすでに給電中でも出さない。満充電のまま給電が続くあいだも出さない。
 */
@Composable
fun rememberChargingSupplyAlert() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val appContext = context.applicationContext
        var powered: Boolean? = null
        fun onBattery(intent: Intent?) {
            if (intent == null) {
                return
            }
            val nowPowered = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val previous = powered
            powered = nowPowered
            if (previous == null || previous == nowPowered) {
                return
            }
            showOsAlert(
                if (nowPowered) {
                    "充電を開始します。"
                } else {
                    "充電を終了します。"
                },
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onBattery(intent)
            }
        }
        val sticky = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onBattery(sticky)
        onDispose { appContext.unregisterReceiver(receiver) }
    }
}
