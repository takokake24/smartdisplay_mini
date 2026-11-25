package app.smartdisplay.jihou

import java.time.DayOfWeek
import java.time.LocalDateTime

fun scheduledJihouAnnouncement(time: LocalDateTime): String {
    return when {
        time.minute == 0 && time.hour % 3 == 0 -> {
            val weekday = time.dayOfWeek.toJapanese()
            val dayNames = holidayAndSeasonalSpeech(time.toLocalDate())
            val dayPart = dayNames?.let { "、$it" }.orEmpty()
            "${time.year}年${time.monthValue}月${time.dayOfMonth}日、$weekday$dayPart、${time.hour}時です。"
        }
        time.minute == 0 -> "${time.hour}時です。"
        else -> "${time.hour}時${time.minute}分です。"
    }
}

fun testJihouAnnouncement(time: LocalDateTime): String {
    return "${time.hour}時${time.minute}分です。"
}

private fun DayOfWeek.toJapanese(): String = when (this) {
    DayOfWeek.MONDAY -> "月曜日"
    DayOfWeek.TUESDAY -> "火曜日"
    DayOfWeek.WEDNESDAY -> "水曜日"
    DayOfWeek.THURSDAY -> "木曜日"
    DayOfWeek.FRIDAY -> "金曜日"
    DayOfWeek.SATURDAY -> "土曜日"
    DayOfWeek.SUNDAY -> "日曜日"
}
