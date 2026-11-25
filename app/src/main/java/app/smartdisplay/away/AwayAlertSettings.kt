package app.smartdisplay.away

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 外出アラート中に一時的に上げるメディア音量。
 * 0.0〜1.0 = 端末最大音量比。
 */
class AwayAlertSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var volumeRatio by mutableFloatStateOf(
        prefs.getFloat(KeyVolumeRatio, DefaultVolumeRatio).coerceIn(0f, 1f),
    )
        private set

    fun updateVolumeRatio(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (volumeRatio == next) {
            return
        }
        volumeRatio = next
        prefs.edit().putFloat(KeyVolumeRatio, next).apply()
    }

    companion object {
        private const val PrefsName = "away_alert_settings"
        private const val KeyVolumeRatio = "volume_ratio"
        const val DefaultVolumeRatio = 1.0f
    }
}

@Composable
fun rememberAwayAlertSettings(): AwayAlertSettings {
    val context = LocalContext.current
    return remember { AwayAlertSettings(context.applicationContext) }
}
