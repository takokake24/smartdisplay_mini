package app.smartdisplay.notification

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 他アプリ通知の読み上げ。
 * [speechEnabled] がオフのときは表示も読み上げもしない。
 * [includeSilent] は読み上げがオンのとき、音の出ない通知も対象にする。
 */
class NotificationAnnounceSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var speechEnabled by mutableStateOf(prefs.getBoolean(KeySpeechEnabled, true))
        private set

    var includeSilent by mutableStateOf(prefs.getBoolean(KeyIncludeSilent, false))
        private set

    fun updateSpeechEnabled(value: Boolean) {
        if (speechEnabled == value) {
            return
        }
        speechEnabled = value
        prefs.edit().putBoolean(KeySpeechEnabled, value).apply()
    }

    fun updateIncludeSilent(value: Boolean) {
        if (includeSilent == value) {
            return
        }
        includeSilent = value
        prefs.edit().putBoolean(KeyIncludeSilent, value).apply()
    }

    private companion object {
        const val PrefsName = "notification_announce_settings"
        const val KeySpeechEnabled = "speech_enabled"
        const val KeyIncludeSilent = "include_silent"
    }
}

@Composable
fun rememberNotificationAnnounceSettings(): NotificationAnnounceSettings {
    val context = LocalContext.current
    return remember { NotificationAnnounceSettings(context.applicationContext) }
}
