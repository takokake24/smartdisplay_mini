package app.smartdisplay.jihou

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.LocalDateTime

private const val PrefsName = "jihou_settings"
private const val KeyEnabled = "enabled"
private const val KeyOverrideThemeDate = "override_theme_date"
private const val KeyOverrideSeason = "override_season"

class JihouSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean(KeyEnabled, true))
        private set

    /** 今日（テーマ日）だけの上書きテーマ。未設定なら null。 */
    var todayOverrideSeason by mutableStateOf(loadOverrideSeason())
        private set

    /** 上書きが紐づくテーマ日。 */
    var todayOverrideThemeDate by mutableStateOf(loadOverrideThemeDate())
        private set

    fun updateEnabled(value: Boolean) {
        if (enabled == value) {
            return
        }
        enabled = value
        prefs.edit().putBoolean(KeyEnabled, value).apply()
    }

    fun automaticSeason(now: LocalDateTime = LocalDateTime.now()): JihouSeason {
        return automaticJihouSeason(jihouThemeDate(now))
    }

    fun effectiveSeason(now: LocalDateTime = LocalDateTime.now()): JihouSeason {
        pruneExpiredOverride(now)
        val themeDate = jihouThemeDate(now)
        val overrideSeason = todayOverrideSeason
        val overrideDate = todayOverrideThemeDate
        if (overrideSeason != null && overrideDate == themeDate) {
            return overrideSeason
        }
        return automaticJihouSeason(themeDate)
    }

    fun hasTodayOverride(now: LocalDateTime = LocalDateTime.now()): Boolean {
        pruneExpiredOverride(now)
        return todayOverrideSeason != null && todayOverrideThemeDate == jihouThemeDate(now)
    }

    /**
     * 今日のテーマ日だけ指定テーマを使う。
     * null、または自動判定と同じテーマなら上書きを解除する。
     */
    fun setTodayOverride(
        season: JihouSeason?,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        pruneExpiredOverride(now)
        val themeDate = jihouThemeDate(now)
        val automatic = automaticJihouSeason(themeDate)
        if (season == null || season == automatic) {
            clearTodayOverride()
            return
        }
        todayOverrideThemeDate = themeDate
        todayOverrideSeason = season
        prefs.edit()
            .putString(KeyOverrideThemeDate, themeDate.toString())
            .putString(KeyOverrideSeason, season.name)
            .apply()
    }

    fun clearTodayOverride() {
        todayOverrideThemeDate = null
        todayOverrideSeason = null
        prefs.edit()
            .remove(KeyOverrideThemeDate)
            .remove(KeyOverrideSeason)
            .apply()
    }

    private fun pruneExpiredOverride(now: LocalDateTime) {
        val overrideDate = todayOverrideThemeDate ?: return
        if (overrideDate != jihouThemeDate(now)) {
            clearTodayOverride()
        }
    }

    private fun loadOverrideThemeDate(): LocalDate? {
        val raw = prefs.getString(KeyOverrideThemeDate, null) ?: return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private fun loadOverrideSeason(): JihouSeason? {
        val raw = prefs.getString(KeyOverrideSeason, null) ?: return null
        return runCatching { JihouSeason.valueOf(raw) }.getOrNull()
    }
}

@Composable
fun rememberJihouSettings(): JihouSettings {
    val context = LocalContext.current
    return remember { JihouSettings(context.applicationContext) }
}
