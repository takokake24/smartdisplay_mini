package app.smartdisplay.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 使うカレンダーを選択する設定。
 * [disabledCalendarIds] に入っているものは使わない（新規カレンダーは既定で有効）。
 */
class CalendarSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var disabledCalendarIds by mutableStateOf(loadDisabledIds())
        private set

    fun isEnabled(calendarId: Long): Boolean {
        return calendarId !in disabledCalendarIds
    }

    fun setEnabled(calendarId: Long, enabled: Boolean) {
        val next = disabledCalendarIds.toMutableSet()
        if (enabled) {
            next.remove(calendarId)
        } else {
            next.add(calendarId)
        }
        disabledCalendarIds = next
        prefs.edit().putStringSet(KeyDisabledIds, next.map { it.toString() }.toSet()).apply()
    }

    fun enabledIdsAmong(all: List<DeviceCalendar>): Set<Long> {
        return all.asSequence()
            .map { it.id }
            .filter { isEnabled(it) }
            .toSet()
    }

    private fun loadDisabledIds(): Set<Long> {
        return prefs.getStringSet(KeyDisabledIds, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    private companion object {
        const val PrefsName = "calendar_settings"
        const val KeyDisabledIds = "disabled_calendar_ids"
    }
}

@Composable
fun rememberCalendarSettings(): CalendarSettings {
    val context = LocalContext.current
    return remember { CalendarSettings(context.applicationContext) }
}
