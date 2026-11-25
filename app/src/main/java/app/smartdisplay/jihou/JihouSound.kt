package app.smartdisplay.jihou

import app.smartdisplay.R

/** 0 / 15 / 30 / 45 分の通常メロディ。 */
fun melodyResId(quarterIndex: Int): Int {
    return when (quarterIndex.coerceIn(1, 4)) {
        1 -> R.raw.jihou_normal_1
        2 -> R.raw.jihou_normal_2
        3 -> R.raw.jihou_normal_3
        else -> R.raw.jihou_normal_4
    }
}

/** 時報メロディの直前に鳴らす追加音。 */
fun introSoundResId(hour: Int, minute: Int): Int? {
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

val JihouSpecialIntros = listOf(
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
