package app.smartdisplay.weather

/**
 * Open-Meteo / WMO weather interpretation codes の日本語表記。
 * https://open-meteo.com/en/docs
 */
fun weatherDescriptionJa(code: Int): String {
    return when (code) {
        0 -> "晴れ"
        1 -> "晴れ時々くもり"
        2 -> "くもり時々晴れ"
        3 -> "くもり"
        45, 48 -> "霧"
        51, 53, 55 -> "霧雨"
        56, 57 -> "着氷性の霧雨"
        61 -> "弱い雨"
        63 -> "雨"
        65 -> "強い雨"
        66, 67 -> "着氷性の雨"
        71 -> "弱い雪"
        73 -> "雪"
        75 -> "強い雪"
        77 -> "霧雪"
        80 -> "にわか雨"
        81 -> "強いにわか雨"
        82 -> "激しいにわか雨"
        85 -> "にわか雪"
        86 -> "強いにわか雪"
        95 -> "雷雨"
        96, 99 -> "雷を伴う雹"
        else -> "天候不明"
    }
}
