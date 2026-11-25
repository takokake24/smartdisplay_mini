package app.smartdisplay.audio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * ナビなど、時報・読み上げより優先するアプリのパッケージ名一覧。
 */
class AudioPrioritySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var priorityPackages by mutableStateOf(prefs.getStringSet(KeyPackages, emptySet()).orEmpty().toSet())
        private set

    var onPackagesChanged: (() -> Unit)? = null

    fun isPriority(packageName: String): Boolean = packageName in priorityPackages

    fun setPriority(packageName: String, enabled: Boolean) {
        val next = if (enabled) {
            priorityPackages + packageName
        } else {
            priorityPackages - packageName
        }
        if (next == priorityPackages) {
            return
        }
        priorityPackages = next
        prefs.edit().putStringSet(KeyPackages, next).apply()
        onPackagesChanged?.invoke()
    }

    private companion object {
        const val PrefsName = "audio_priority_settings"
        const val KeyPackages = "priority_packages"
    }
}

@Composable
fun rememberAudioPrioritySettings(): AudioPrioritySettings {
    val context = LocalContext.current
    return remember { AudioPrioritySettings(context.applicationContext) }
}
