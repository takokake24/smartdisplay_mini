package app.smartdisplay.battery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class BatteryAnnounceMode {
    Always,
    Resident,
    Off,
}

/**
 * 電池残量の読み上げ。
 * [Always] は起動中・常駐時とも読む。[Resident] は常駐時だけ。[Off] は読まない。
 */
class BatteryAnnounceSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var mode by mutableStateOf(readMode(prefs.getString(KeyMode, DefaultModeName)))
        private set

    fun updateMode(value: BatteryAnnounceMode) {
        if (mode == value) {
            return
        }
        mode = value
        prefs.edit().putString(KeyMode, value.name).apply()
    }

    private companion object {
        const val PrefsName = "battery_announce_settings"
        const val KeyMode = "mode"
        val DefaultModeName = BatteryAnnounceMode.Resident.name

        fun readMode(raw: String?): BatteryAnnounceMode {
            return BatteryAnnounceMode.entries.firstOrNull { it.name == raw }
                ?: BatteryAnnounceMode.Resident
        }
    }
}
