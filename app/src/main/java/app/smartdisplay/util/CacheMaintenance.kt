package app.smartdisplay.util

import app.smartdisplay.bus.BusTimetableRepository
import app.smartdisplay.bus.busServiceDate
import app.smartdisplay.train.TrainTimetableRepository
import app.smartdisplay.weather.WeatherRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 機能上消してよいメモリキャッシュを掃除する。
 * - 鉄道時刻表・バス時刻表: 営業日（2時区切り）が変わったら破棄
 * - 天気予報: 取得日が今日でないものだけ削除
 * - 通信ログ: 直近3時間より古いものを削除
 * 設定（SharedPreferences）は永続データなので対象外。
 *
 * 実行タイミングは起動からの経過ではなく、0時・3時・6時…の正時。
 */
fun pruneAppCaches(
    trainTimetableRepository: TrainTimetableRepository,
    busTimetableRepository: BusTimetableRepository,
    weatherRepository: WeatherRepository,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val serviceDate = busServiceDate(now)
    trainTimetableRepository.pruneExpired(serviceDate)
    busTimetableRepository.pruneExpired(serviceDate)
    weatherRepository.pruneExpired(now)
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
