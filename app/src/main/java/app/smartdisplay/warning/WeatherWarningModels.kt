package app.smartdisplay.warning

import java.time.LocalDateTime

enum class WeatherWarningSeverity {
    Special,
    Danger,
    Warning,
    Advisory,
}

data class WeatherWarningKind(
    val code: String,
    val name: String,
    val severity: WeatherWarningSeverity,
)

data class WeatherWarningItem(
    val kind: WeatherWarningKind,
    val status: String,
    val reportAt: LocalDateTime?,
    val detailTexts: List<String> = emptyList(),
) {
    val code: String get() = kind.code
    val name: String get() = kind.name
    val severity: WeatherWarningSeverity get() = kind.severity
}

fun weatherWarningKindOf(code: String): WeatherWarningKind? {
    return WeatherWarningKinds[code]
}

fun buildWeatherWarningIssueSpeech(item: WeatherWarningItem): String {
    val detail = item.detailTexts.joinToString("、").takeIf { it.isNotBlank() }
    val detailPart = if (detail != null) "${detail}。" else ""
    val closing = when (item.severity) {
        WeatherWarningSeverity.Special,
        WeatherWarningSeverity.Danger,
        -> "今後の状況に警戒してください。"
        WeatherWarningSeverity.Warning -> "今後の状況に十分ご注意ください。"
        WeatherWarningSeverity.Advisory -> "ご注意ください。"
    }
    return "${item.name}が発令されました。${detailPart}$closing"
}

fun buildWeatherWarningCancelSpeech(item: WeatherWarningItem): String {
    return "${item.name}が解除されました。"
}

private val WeatherWarningKinds = mapOf(
    "33" to WeatherWarningKind("33", "大雨特別警報", WeatherWarningSeverity.Special),
    "39" to WeatherWarningKind("39", "土砂災害特別警報", WeatherWarningSeverity.Special),
    "38" to WeatherWarningKind("38", "高潮特別警報", WeatherWarningSeverity.Special),
    "35" to WeatherWarningKind("35", "暴風特別警報", WeatherWarningSeverity.Special),
    "32" to WeatherWarningKind("32", "暴風雪特別警報", WeatherWarningSeverity.Special),
    "36" to WeatherWarningKind("36", "大雪特別警報", WeatherWarningSeverity.Special),
    "37" to WeatherWarningKind("37", "波浪特別警報", WeatherWarningSeverity.Special),
    "43" to WeatherWarningKind("43", "大雨危険警報", WeatherWarningSeverity.Danger),
    "49" to WeatherWarningKind("49", "土砂災害危険警報", WeatherWarningSeverity.Danger),
    "48" to WeatherWarningKind("48", "高潮危険警報", WeatherWarningSeverity.Danger),
    "03" to WeatherWarningKind("03", "大雨警報", WeatherWarningSeverity.Warning),
    "09" to WeatherWarningKind("09", "土砂災害警報", WeatherWarningSeverity.Warning),
    "08" to WeatherWarningKind("08", "高潮警報", WeatherWarningSeverity.Warning),
    "05" to WeatherWarningKind("05", "暴風警報", WeatherWarningSeverity.Warning),
    "02" to WeatherWarningKind("02", "暴風雪警報", WeatherWarningSeverity.Warning),
    "06" to WeatherWarningKind("06", "大雪警報", WeatherWarningSeverity.Warning),
    "07" to WeatherWarningKind("07", "波浪警報", WeatherWarningSeverity.Warning),
    "10" to WeatherWarningKind("10", "大雨注意報", WeatherWarningSeverity.Advisory),
    "29" to WeatherWarningKind("29", "土砂災害注意報", WeatherWarningSeverity.Advisory),
    "19" to WeatherWarningKind("19", "高潮注意報", WeatherWarningSeverity.Advisory),
    "15" to WeatherWarningKind("15", "強風注意報", WeatherWarningSeverity.Advisory),
    "13" to WeatherWarningKind("13", "風雪注意報", WeatherWarningSeverity.Advisory),
    "12" to WeatherWarningKind("12", "大雪注意報", WeatherWarningSeverity.Advisory),
    "16" to WeatherWarningKind("16", "波浪注意報", WeatherWarningSeverity.Advisory),
    "14" to WeatherWarningKind("14", "雷注意報", WeatherWarningSeverity.Advisory),
    "17" to WeatherWarningKind("17", "融雪注意報", WeatherWarningSeverity.Advisory),
    "18" to WeatherWarningKind("18", "洪水注意報", WeatherWarningSeverity.Advisory),
    "20" to WeatherWarningKind("20", "濃霧注意報", WeatherWarningSeverity.Advisory),
    "21" to WeatherWarningKind("21", "乾燥注意報", WeatherWarningSeverity.Advisory),
    "22" to WeatherWarningKind("22", "なだれ注意報", WeatherWarningSeverity.Advisory),
    "23" to WeatherWarningKind("23", "低温注意報", WeatherWarningSeverity.Advisory),
    "24" to WeatherWarningKind("24", "霜注意報", WeatherWarningSeverity.Advisory),
    "25" to WeatherWarningKind("25", "着氷注意報", WeatherWarningSeverity.Advisory),
    "26" to WeatherWarningKind("26", "着雪注意報", WeatherWarningSeverity.Advisory),
)
