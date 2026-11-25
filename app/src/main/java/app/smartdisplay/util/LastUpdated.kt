package app.smartdisplay.util

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 画面向けの静かな最終更新表示。
 * @param staleAfterMinutes この分数を超えたら「古い可能性」を添える
 */
fun formatLastUpdatedLabel(
    fetchedAt: LocalDateTime?,
    now: LocalDateTime = LocalDateTime.now(),
    staleAfterMinutes: Long = 20L,
): String {
    if (fetchedAt == null) {
        return "最終更新 未取得"
    }
    val minutes = ChronoUnit.MINUTES.between(fetchedAt, now).coerceAtLeast(0)
    val base = when {
        minutes < 1L -> "最終更新 たった今"
        minutes < 60L -> "最終更新 ${minutes}分前"
        else -> {
            val hours = minutes / 60L
            "最終更新 ${hours}時間前"
        }
    }
    return if (minutes >= staleAfterMinutes) {
        "$base（古い可能性）"
    } else {
        base
    }
}
