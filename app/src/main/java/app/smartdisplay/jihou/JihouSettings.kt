package app.smartdisplay.jihou

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val PrefsName = "jihou_settings"
private const val KeyEnabled = "enabled"

class JihouSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean(KeyEnabled, true))
        private set

    fun updateEnabled(value: Boolean) {
        if (enabled == value) {
            return
        }
        enabled = value
        prefs.edit().putBoolean(KeyEnabled, value).apply()
    }
}

@Composable
fun rememberJihouSettings(): JihouSettings {
    val context = LocalContext.current
    return remember { JihouSettings(context.applicationContext) }
}
