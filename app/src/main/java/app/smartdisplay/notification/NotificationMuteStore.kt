package app.smartdisplay.notification

import android.content.Context
import app.smartdisplay.jihou.jihouThemeDate
import java.time.LocalDateTime

/**
 * 「今日は通知しない」の「今日」は時報テーマ日（6時切替）に合わせる。
 */
class NotificationMuteStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    fun isMutedToday(category: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val themeDay = jihouThemeDate(now)
        return prefs.getString(key(category), null) == themeDay.toString()
    }

    fun muteToday(category: String, now: LocalDateTime = LocalDateTime.now()) {
        val themeDay = jihouThemeDate(now)
        prefs.edit().putString(key(category), themeDay.toString()).apply()
    }

    private fun key(category: String): String = "mute_$category"

    private companion object {
        const val PrefsName = "notification_mute"
    }
}
