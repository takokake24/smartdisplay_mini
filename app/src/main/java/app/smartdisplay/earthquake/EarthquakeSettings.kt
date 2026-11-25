package app.smartdisplay.earthquake

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 緊急警報（緊急地震速報・気象特別／危険警報）時に一時的に上げるメディア音量。
 * 0.0〜1.0 = 端末最大音量比。
 */
class EarthquakeSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var eewVolumeRatio by mutableFloatStateOf(
        prefs.getFloat(KeyEewVolumeRatio, DefaultVolumeRatio).coerceIn(0f, 1f),
    )
        private set

    fun updateEewVolumeRatio(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (eewVolumeRatio == next) {
            return
        }
        eewVolumeRatio = next
        prefs.edit().putFloat(KeyEewVolumeRatio, next).apply()
    }

    companion object {
        private const val PrefsName = "earthquake_settings"
        private const val KeyEewVolumeRatio = "eew_volume_ratio"
        const val DefaultVolumeRatio = 1.0f
    }
}

@Composable
fun rememberEarthquakeSettings(): EarthquakeSettings {
    val context = LocalContext.current
    return remember { EarthquakeSettings(context.applicationContext) }
}
