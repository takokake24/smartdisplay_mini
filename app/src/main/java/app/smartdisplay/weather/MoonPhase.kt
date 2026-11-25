package app.smartdisplay.weather

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * 簡易的な月齢計算（表示用）。
 * 既知の新月からの日数で位相を近似する。
 */
fun moonPhaseInfo(date: LocalDate = LocalDate.now()): MoonPhaseInfo {
    val knownNewMoon = LocalDate.of(2000, 1, 6)
    val synodicDays = 29.530588853
    val days = ChronoUnit.DAYS.between(knownNewMoon, date).toDouble()
    val age = ((days % synodicDays) + synodicDays) % synodicDays
    val illumination = ((1.0 - kotlin.math.cos(age / synodicDays * 2.0 * Math.PI)) / 2.0 * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
    val label = when {
        age < 1.85 -> "新月"
        age < 5.53 -> "三日月"
        age < 9.22 -> "上弦の月"
        age < 12.90 -> "十三夜"
        age < 16.59 -> "満月"
        age < 20.27 -> "十六夜"
        age < 23.95 -> "下弦の月"
        age < 27.64 -> "有明月"
        else -> "新月"
    }
    return MoonPhaseInfo(label = label, illuminationPercent = illumination)
}
