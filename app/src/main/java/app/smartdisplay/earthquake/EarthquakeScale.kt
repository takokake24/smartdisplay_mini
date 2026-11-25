package app.smartdisplay.earthquake

/**
 * P2P地震情報 / 気象庁の震度コード。
 * 0=震度0, 10=1, 20=2, 30=3, 40=4, 45=5弱, 50=5強, 55=6弱, 60=6強, 70=7
 */
fun intensityLabel(scale: Int): String {
    return when (scale) {
        0 -> "0"
        10 -> "1"
        20 -> "2"
        30 -> "3"
        40 -> "4"
        45 -> "5弱"
        50 -> "5強"
        55 -> "6弱"
        60 -> "6強"
        70 -> "7"
        else -> if (scale < 0) "不明" else scale.toString()
    }
}

/** 画面中央向けの大きな震度表示（半角。例: 3 / 5+）。 */
fun intensityDisplayGlyph(scale: Int): String {
    return when (scale) {
        0 -> "0"
        10 -> "1"
        20 -> "2"
        30 -> "3"
        40 -> "4"
        45 -> "5-"
        50 -> "5+"
        55 -> "6-"
        60 -> "6+"
        70 -> "7"
        else -> intensityLabel(scale)
    }
}

fun intensitySpeech(scale: Int): String {
    return when (scale) {
        0 -> "0"
        10 -> "1"
        20 -> "2"
        30 -> "3"
        40 -> "4"
        45 -> "5弱"
        50 -> "5強"
        55 -> "6弱"
        60 -> "6強"
        70 -> "7"
        else -> "不明"
    }
}
