package app.smartdisplay.train

import app.smartdisplay.bus.busServiceDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.coroutines.coroutineContext

/**
 * 鉄道時刻表の取得と営業日単位のメモリキャッシュ。
 * 同じ日付・方面は「更新」しない限り、その営業日中は再取得しない。
 */
class TrainTimetableRepository(
    private val client: YahooTrainTimetableClient = YahooTrainTimetableClient(),
) {
    private val lock = Any()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<CacheKey, Deferred<TrainTimetableDay>>()

    /** キャッシュが有効な営業日（2時区切り）。変わったら破棄する。 */
    private var cacheServiceDay: LocalDate? = null
    private val cache = mutableMapOf<CacheKey, TrainTimetableDay>()

    fun cachedDay(
        date: LocalDate,
        direction: TrainDirection,
        today: LocalDate = busServiceDate(LocalDateTime.now()),
    ): TrainTimetableDay? {
        synchronized(lock) {
            if (cacheServiceDay != today) {
                return null
            }
            return cache[CacheKey(date, direction)]
        }
    }

    suspend fun fetchDay(
        date: LocalDate,
        direction: TrainDirection,
        forceRefresh: Boolean = false,
        today: LocalDate = busServiceDate(LocalDateTime.now()),
    ): TrainTimetableDay {
        val key = CacheKey(date, direction)
        val toAwait: Deferred<TrainTimetableDay> = synchronized(lock) {
            rollServiceDayLocked(today)
            if (forceRefresh) {
                inFlight.remove(key)?.cancel()
                cache.remove(key)
            } else {
                cache[key]?.let { return it }
                inFlight[key]?.takeIf { it.isActive }?.let { return@synchronized it }
            }
            fetchScope.async {
                val self = coroutineContext[Job]
                try {
                    val day = TrainTimetableDay(
                        date = date,
                        direction = direction,
                        trips = client.fetch(date, direction),
                    )
                    synchronized(lock) {
                        rollServiceDayLocked(busServiceDate(LocalDateTime.now()))
                        cache[key] = day
                    }
                    day
                } finally {
                    synchronized(lock) {
                        if (inFlight[key] === self) {
                            inFlight.remove(key)
                        }
                    }
                }
            }.also { inFlight[key] = it }
        }
        return toAwait.await()
    }

    suspend fun fetchStops(
        date: LocalDate,
        direction: TrainDirection,
        trip: TrainTrip,
    ): TrainRoute = client.fetchStops(date, direction, trip)

    /** 営業日が変わったときだけキャッシュを捨てる。 */
    fun pruneExpired(today: LocalDate = busServiceDate(LocalDateTime.now())) {
        synchronized(lock) {
            rollServiceDayLocked(today)
        }
    }

    private fun rollServiceDayLocked(today: LocalDate) {
        if (cacheServiceDay == today) {
            return
        }
        cache.clear()
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        cacheServiceDay = today
    }

    private data class CacheKey(
        val date: LocalDate,
        val direction: TrainDirection,
    )
}
