package app.smartdisplay.jihou

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month

/** 時報テーマ日の切り替わり時刻（この正時から新テーマ）。 */
const val JihouThemeSwitchHour = 6

/**
 * 時報テーマ判定に使う「テーマ日」。
 * 毎日 6:00 の時報からその暦日のテーマを使う（6時未満は前日扱い）。
 */
fun jihouThemeDate(now: LocalDateTime): LocalDate {
    val date = now.toLocalDate()
    return if (now.hour < JihouThemeSwitchHour) {
        date.minusDays(1)
    } else {
        date
    }
}

fun isChristmasThemeDate(themeDate: LocalDate): Boolean {
    return themeDate.month == Month.DECEMBER && themeDate.dayOfMonth in 1..25
}

fun automaticJihouSeason(themeDate: LocalDate): JihouSeason {
    return when {
        // 祝日は土日でもクリスマス期間でも祝日モードを優先する
        isJapanesePublicHoliday(themeDate) -> JihouSeason.Holiday
        isChristmasThemeDate(themeDate) -> JihouSeason.Christmas
        isWeekendOrHoliday(themeDate) -> JihouSeason.Weekend
        else -> JihouSeason.Normal
    }
}
