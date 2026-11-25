package app.smartdisplay.expiration

import app.smartdisplay.notification.ExpirationNotificationItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 今日を含めてこの日数までを、期限が近い色にする。 */
const val ExpirationSoonDays = 7

enum class ExpirationUrgency {
    Expired,
    Soon,
    Normal,
}

data class ExpirationItem(
    val id: Long,
    val name: String,
    val quantity: Int,
    val expirationDate: LocalDate?,
    val serverRemainingDays: Int,
) {
    fun remainingDaysOn(today: LocalDate): Int {
        val date = expirationDate ?: return serverRemainingDays
        return ChronoUnit.DAYS.between(today, date).toInt()
    }
}

data class ExpirationAnnouncement(
    val body: String,
    val speechText: String,
    val items: List<ExpirationNotificationItem>,
)

fun expirationUrgency(remainingDays: Int): ExpirationUrgency {
    return when {
        remainingDays < 0 -> ExpirationUrgency.Expired
        remainingDays <= ExpirationSoonDays -> ExpirationUrgency.Soon
        else -> ExpirationUrgency.Normal
    }
}

fun expirationDigits(count: Int): String {
    val digits = kotlin.math.abs(count).toString()
    if (digits.length < 4) {
        return digits
    }
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

fun expirationRemainingLabel(remainingDays: Int): String {
    return when {
        remainingDays < 0 -> "${expirationDigits(remainingDays)}日超過"
        remainingDays == 0 -> "今日"
        else -> "あと${expirationDigits(remainingDays)}日"
    }
}

fun expirationDateLabel(date: LocalDate, today: LocalDate, includeYear: Boolean): String {
    val pattern = if (includeYear && date.year != today.year) {
        "yyyy年M月d日"
    } else {
        "M月d日"
    }
    return date.format(DateTimeFormatter.ofPattern(pattern))
}

fun sortedExpirations(items: List<ExpirationItem>): List<ExpirationItem> {
    return items.sortedWith(
        compareBy<ExpirationItem> { it.expirationDate ?: LocalDate.MAX }
            .thenBy { it.name }
            .thenBy { it.id },
    )
}

/**
 * 近い期限が一つもなければ null。案内そのものを出さない。
 */
fun buildExpirationAnnouncement(
    items: List<ExpirationItem>,
    today: LocalDate,
): ExpirationAnnouncement? {
    val sorted = sortedExpirations(items)
    if (sorted.isEmpty()) {
        return null
    }
    val notices = sorted.map { item ->
        val days = item.remainingDaysOn(today)
        ExpirationNotificationItem(
            name = item.name,
            quantity = item.quantity,
            dateLabel = item.expirationDate?.let {
                expirationDateLabel(it, today, includeYear = true)
            } ?: "日付不明",
            remainingDays = days,
        )
    }
    val body = buildString {
        appendLine("以下の賞味期限が近づいています。")
        appendLine()
        notices.forEach { item ->
            val remain = if (item.remainingDays == 0) {
                "今日"
            } else {
                expirationRemainingLabel(item.remainingDays)
            }
            appendLine("- ${item.name}（${item.quantity}個：${item.dateLabel} $remain）")
        }
    }.trimEnd()
    val speech = buildString {
        append("賞味期限が近いものがあります。")
        sorted.forEachIndexed { index, item ->
            append(
                expirationSpeechLine(
                    item = item,
                    days = item.remainingDaysOn(today),
                    last = index == sorted.lastIndex,
                ),
            )
        }
    }
    return ExpirationAnnouncement(
        body = body,
        speechText = speech,
        items = notices,
    )
}

private fun expirationSpeechLine(item: ExpirationItem, days: Int, last: Boolean): String {
    val end = if (last) "です。" else "。"
    return when {
        days < 0 -> "${item.name}、${item.quantity}個は、期限を${-days}日過ぎています。"
        days == 0 -> "${item.name}、${item.quantity}個が、今日まで$end"
        else -> "${item.name}、${item.quantity}個が、あと${days}日$end"
    }
}
