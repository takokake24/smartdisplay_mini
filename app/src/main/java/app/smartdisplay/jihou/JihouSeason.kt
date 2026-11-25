package app.smartdisplay.jihou

import app.smartdisplay.R

/**
 * 時期ごとの時報セット。
 */
enum class JihouSeason {
    /** 平日（コーヒールンバ） */
    Normal,
    /** 土日（銀河鉄道）。祝日は [Holiday] */
    Weekend,
    /** 祝日。土日・クリスマスより優先 */
    Holiday,
    /** 12/1〜12/25（クリスマス）。祝日は [Holiday] */
    Christmas,
}

val JihouSeason.displayName: String
    get() = when (this) {
        JihouSeason.Normal -> "通常"
        JihouSeason.Weekend -> "土日"
        JihouSeason.Holiday -> "祝日"
        JihouSeason.Christmas -> "クリスマス"
    }

fun JihouSeason.soundResId(quarterIndex: Int): Int {
    val index = quarterIndex.coerceIn(1, 4)
    return when (this) {
        JihouSeason.Normal -> when (index) {
            1 -> R.raw.jihou_normal_1
            2 -> R.raw.jihou_normal_2
            3 -> R.raw.jihou_normal_3
            else -> R.raw.jihou_normal_4
        }
        JihouSeason.Weekend -> when (index) {
            1 -> R.raw.jihou_weekend_1
            2 -> R.raw.jihou_weekend_2
            3 -> R.raw.jihou_weekend_3
            else -> R.raw.jihou_weekend_4
        }
        JihouSeason.Christmas -> when (index) {
            1 -> R.raw.jihou_christmas_1
            2 -> R.raw.jihou_christmas_2
            3 -> R.raw.jihou_christmas_3
            else -> R.raw.jihou_christmas_4
        }
        JihouSeason.Holiday -> when (index) {
            1 -> R.raw.jihou_holiday_1
            2 -> R.raw.jihou_holiday_2
            3 -> R.raw.jihou_holiday_3
            else -> R.raw.jihou_holiday_4
        }
    }
}

/**
 * 時報メロディの直前に鳴らす追加音。
 * 特殊時報はテーマ共通。
 */
fun JihouSeason.introSoundResId(hour: Int, minute: Int): Int? {
    return sharedSpecialIntroResId(hour, minute)
}

private fun sharedSpecialIntroResId(hour: Int, minute: Int): Int? {
    return when (hour to minute) {
        0 to 0 -> R.raw.jihou_intro_kakko
        3 to 0 -> R.raw.jihou_intro_story02
        8 to 0 -> R.raw.jihou_intro_story01
        10 to 0 -> R.raw.jihou_intro_takarajima1
        12 to 0 -> R.raw.jihou_intro_kusakeiba
        12 to 30 -> R.raw.jihou_intro_takarajima2
        13 to 30 -> R.raw.jihou_intro_takarajima3
        18 to 0 -> R.raw.jihou_intro_tetris
        else -> null
    }
}

data class JihouSpecialIntro(
    val hour: Int,
    val minute: Int,
) {
    val timeLabel: String
        get() = if (minute == 0) {
            "${hour}時"
        } else {
            "${hour}時${minute}分"
        }
}

fun JihouSeason.specialIntros(): List<JihouSpecialIntro> {
    return SharedSpecialIntros
}

private val SharedSpecialIntros = listOf(
    JihouSpecialIntro(hour = 0, minute = 0),
    JihouSpecialIntro(hour = 3, minute = 0),
    JihouSpecialIntro(hour = 8, minute = 0),
    JihouSpecialIntro(hour = 10, minute = 0),
    JihouSpecialIntro(hour = 12, minute = 0),
    JihouSpecialIntro(hour = 12, minute = 30),
    JihouSpecialIntro(hour = 13, minute = 30),
    JihouSpecialIntro(hour = 18, minute = 0),
)

fun quarterIndexForMinute(minute: Int): Int? {
    return when (minute) {
        0 -> 1
        15 -> 2
        30 -> 3
        45 -> 4
        else -> null
    }
}
