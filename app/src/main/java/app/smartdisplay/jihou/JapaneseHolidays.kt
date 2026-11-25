package app.smartdisplay.jihou

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor

/**
 * 日本の国民の祝日（振替休日・国民の休日含む）の簡易判定。
 * 時報テーマ切替・日付読み上げ用。法令の厳密な再計算が必要な場合は更新する。
 */
fun isJapanesePublicHoliday(date: LocalDate): Boolean {
    return japaneseHolidayName(date) != null
}

/** 祝日名。祝日でなければ null。 */
fun japaneseHolidayName(date: LocalDate): String? {
    return japaneseHolidaysIn(date.year)[date]
}

internal fun japaneseHolidaysOf(year: Int): Map<LocalDate, String> {
    return japaneseHolidaysIn(year)
}

fun isWeekendOrHoliday(date: LocalDate): Boolean {
    return date.dayOfWeek == DayOfWeek.SATURDAY ||
        date.dayOfWeek == DayOfWeek.SUNDAY ||
        isJapanesePublicHoliday(date)
}

private fun japaneseHolidaysIn(year: Int): Map<LocalDate, String> {
    val fixed = linkedMapOf(
        LocalDate.of(year, Month.JANUARY, 1) to "元日",
        LocalDate.of(year, Month.FEBRUARY, 11) to "建国記念の日",
        LocalDate.of(year, Month.FEBRUARY, 23) to "天皇誕生日",
        LocalDate.of(year, Month.APRIL, 29) to "昭和の日",
        LocalDate.of(year, Month.MAY, 3) to "憲法記念日",
        LocalDate.of(year, Month.MAY, 4) to "みどりの日",
        LocalDate.of(year, Month.MAY, 5) to "こどもの日",
        LocalDate.of(year, Month.AUGUST, 11) to "山の日",
        LocalDate.of(year, Month.NOVEMBER, 3) to "文化の日",
        LocalDate.of(year, Month.NOVEMBER, 23) to "勤労感謝の日",
    )
    fixed[nthWeekday(year, Month.JANUARY, 2, DayOfWeek.MONDAY)] = "成人の日"
    fixed[nthWeekday(year, Month.JULY, 3, DayOfWeek.MONDAY)] = "海の日"
    fixed[nthWeekday(year, Month.SEPTEMBER, 3, DayOfWeek.MONDAY)] = "敬老の日"
    fixed[nthWeekday(year, Month.OCTOBER, 2, DayOfWeek.MONDAY)] = "スポーツの日"
    fixed[LocalDate.of(year, Month.MARCH, vernalEquinoxDay(year))] = "春分の日"
    fixed[LocalDate.of(year, Month.SEPTEMBER, autumnalEquinoxDay(year))] = "秋分の日"

    val withSubstitute = linkedMapOf<LocalDate, String>()
    withSubstitute.putAll(fixed)
    for ((day, _) in fixed) {
        if (day.dayOfWeek == DayOfWeek.SUNDAY) {
            val substitute = day.plusDays(1)
            if (substitute !in withSubstitute) {
                withSubstitute[substitute] = "振替休日"
            }
        }
    }
    // 国民の休日: 祝日に挟まれた平日
    val citizens = linkedMapOf<LocalDate, String>()
    for (day in withSubstitute.keys) {
        val mid = day.plusDays(1)
        if (mid !in withSubstitute && mid.plusDays(1) in withSubstitute) {
            citizens[mid] = "国民の休日"
        }
    }
    return withSubstitute + citizens
}

private fun nthWeekday(year: Int, month: Month, n: Int, dayOfWeek: DayOfWeek): LocalDate {
    return LocalDate.of(year, month, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(n, dayOfWeek))
}

internal fun vernalEquinoxDate(year: Int): LocalDate {
    return LocalDate.of(year, Month.MARCH, vernalEquinoxDay(year))
}

internal fun autumnalEquinoxDate(year: Int): LocalDate {
    return LocalDate.of(year, Month.SEPTEMBER, autumnalEquinoxDay(year))
}

private fun vernalEquinoxDay(year: Int): Int {
    return floor(20.8431 + 0.242194 * (year - 1980) - floor((year - 1980) / 4.0)).toInt()
}

private fun autumnalEquinoxDay(year: Int): Int {
    return floor(23.2488 + 0.242194 * (year - 1980) - floor((year - 1980) / 4.0)).toInt()
}
