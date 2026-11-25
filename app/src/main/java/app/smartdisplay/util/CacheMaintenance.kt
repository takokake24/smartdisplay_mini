package app.smartdisplay.util

import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 通信ログのうち、直近3時間より古いものを削除する。
 * 設定（SharedPreferences）は永続データなので対象外。
 *
 * 実行タイミングは起動からの経過ではなく、0時・3時・6時…の正時。
 */
fun pruneAppCaches(now: LocalDateTime = LocalDateTime.now()) {
    NetworkLog.prune(now)
}

/** 次の 0/3/6/9/12/15/18/21 時までの待ち時間（ミリ秒）。 */
fun millisUntilNextCacheMaintenance(now: LocalDateTime = LocalDateTime.now()): Long {
    val truncated = now.truncatedTo(ChronoUnit.HOURS)
    val nextHour = ((truncated.hour / 3) + 1) * 3
    val target = if (nextHour >= 24) {
        truncated.toLocalDate().plusDays(1).atStartOfDay()
    } else {
        truncated.withHour(nextHour)
    }
    return Duration.between(now, target).toMillis().coerceAtLeast(1L)
}
