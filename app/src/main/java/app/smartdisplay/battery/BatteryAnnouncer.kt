package app.smartdisplay.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.smartdisplay.notification.NotificationCategoryGeneral
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect

/**
 * 充電の開始・終了と、指定残量への到達を読み上げる。
 * バナーは出さず、通知と同じ待ち列に音声だけ載せる。
 */
class BatteryAnnouncer(
    context: Context,
    private val notifications: NotificationCenter,
    private val settings: BatteryAnnounceSettings,
    private val isResident: () -> Boolean,
) {
    private val appContext = context.applicationContext
    private var started = false
    private var percent: Int? = null
    private var powered: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) {
                return
            }
            onBattery(intent)
        }
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        val sticky = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onBattery(sticky, announce = false)
    }

    fun stop() {
        if (!started) {
            return
        }
        started = false
        runCatching { appContext.unregisterReceiver(receiver) }
        percent = null
        powered = null
    }

    private fun onBattery(intent: Intent?, announce: Boolean = true) {
        if (intent == null) {
            return
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val nextPercent = if (level < 0) {
            percent ?: return
        } else {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val nextPowered = plugged != 0
        val previousPercent = percent
        val previousPowered = powered
        percent = nextPercent
        powered = nextPowered
        if (!announce || previousPercent == null || previousPowered == null) {
            return
        }
        if (!shouldAnnounceNow()) {
            return
        }
        if (!previousPowered && nextPowered) {
            enqueueChargeStarted(plugged)
        }
        if (previousPowered && !nextPowered) {
            enqueue("充電を終了します。")
        }
        if (nextPowered && previousPercent != nextPercent) {
            for (threshold in ChargeUpThresholds) {
                if (previousPercent < threshold && nextPercent >= threshold) {
                    enqueue("バッテリー残量が、$threshold%になりました。")
                }
            }
        }
        if (!nextPowered && previousPercent != nextPercent) {
            for ((threshold, suffix) in DischargeDownThresholds) {
                if (previousPercent > threshold && nextPercent <= threshold) {
                    enqueue("バッテリー残量が、$threshold%になりました。$suffix")
                }
            }
        }
    }

    private fun shouldAnnounceNow(): Boolean {
        return when (settings.mode) {
            BatteryAnnounceMode.Off -> false
            BatteryAnnounceMode.Always -> true
            BatteryAnnounceMode.Resident -> isResident()
        }
    }

    private fun enqueueChargeStarted(plugged: Int) {
        val deferToOs = ChargingSounds.osChargingSoundEnabled(appContext)
        val wireless = plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        val wait = if (deferToOs) {
            ChargingSounds.waitMillis(appContext, wireless)
        } else {
            0L
        }
        notifications.post(
            category = NotificationCategoryGeneral,
            title = "電池",
            body = "充電を開始します。",
            speechText = "充電を開始します。",
            soundEffect = if (deferToOs) {
                NotificationSoundEffect.None
            } else {
                NotificationSoundEffect.Battery
            },
            speakNotBeforeElapsed = if (wait > 0L) {
                SystemClock.elapsedRealtime() + wait
            } else {
                0L
            },
            showBanner = false,
        )
    }

    private fun enqueue(speech: String) {
        notifications.post(
            category = NotificationCategoryGeneral,
            title = "電池",
            body = speech,
            speechText = speech,
            soundEffect = NotificationSoundEffect.Battery,
            showBanner = false,
        )
    }

    private companion object {
        val ChargeUpThresholds = listOf(70, 80, 90, 100)
        val DischargeDownThresholds = listOf(
            20 to "",
            10 to "充電してください。",
            5 to "充電してください。",
        )
    }
}
