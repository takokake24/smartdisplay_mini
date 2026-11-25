package app.smartdisplay.bus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * バス時刻表の取得と営業日単位のメモリキャッシュ。
 * 同じ日付・方面は「更新」しない限り、その営業日中は再取得しない。
 */
class BusTimetableRepository(
    private val keio: KeioBusClient = KeioBusClient(),
    private val tachikawa: TachikawaBusClient = TachikawaBusClient(),
    private val seibu: SeibuBusClient = SeibuBusClient(),
    private val ginga: GingaBusClient = GingaBusClient(),
    private val bunbus: BunbusClient = BunbusClient(),
    private val kanto: KantoBusClient = KantoBusClient(),
    private val cocobus: CocobusClient = CocobusClient(),
    private val limousine: LimousineBusClient = LimousineBusClient(),
    private val bushikaku: BushikakuBusClient = BushikakuBusClient(),
) {
    private val lock = Any()
    /** 画面を離れても取得を続け、完了分はキャッシュに載せる。 */
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<CacheKey, Deferred<BusTimetableDay>>()

    /** キャッシュが有効な営業日（2時区切り）。変わったら破棄する。 */
    private var cacheServiceDay: LocalDate? = null
    private val cache = mutableMapOf<CacheKey, BusTimetableDay>()

    fun cachedDay(
        date: LocalDate,
        direction: BusDirection,
        today: LocalDate = busServiceDate(LocalDateTime.now()),
    ): BusTimetableDay? {
        synchronized(lock) {
            if (cacheServiceDay != today) {
                return null
            }
            return cache[CacheKey(date, direction)]
        }
    }

    suspend fun fetchDay(
        date: LocalDate,
        direction: BusDirection,
        forceRefresh: Boolean = false,
        today: LocalDate = busServiceDate(LocalDateTime.now()),
    ): BusTimetableDay {
        val key = CacheKey(date, direction)
        val toAwait: Deferred<BusTimetableDay> = synchronized(lock) {
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
                    val day = fetchRemote(date, direction)
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

    suspend fun fetchStops(trip: BusTrip): List<BusStop> = withContext(Dispatchers.IO) {
        BusStopList.load(trip.stopRef, keio)
    }

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

    private suspend fun fetchRemote(date: LocalDate, direction: BusDirection): BusTimetableDay =
        coroutineScope {
            when (direction) {
                BusDirection.KokubunjiMusashiKoganei -> {
                    val keioDeferred = async { catchCompany { keio.fetchKokubunjiDirection(date) } }
                    val tachikawaDeferred = async { catchCompany { tachikawa.fetchKokubunjiDirection(date) } }
                    val keioResult = keioDeferred.await()
                    val tachikawaResult = tachikawaDeferred.await()
                    BusTimetableDay(
                        date = date,
                        direction = direction,
                        trips = (keioResult.getOrDefault(emptyList()) + tachikawaResult.getOrDefault(emptyList()))
                            .sortedBy { it.departureAt },
                        failures = buildMap {
                            if (keioResult.isFailure) put(BusCompany.Keio, true)
                            if (tachikawaResult.isFailure) put(BusCompany.Tachikawa, true)
                        },
                    )
                }
                BusDirection.HanakoganeiShowa -> {
                    val keioDeferred = async { catchCompany { keio.fetchHanakoganeiDirection(date) } }
                    val tachikawaDeferred = async { catchCompany { tachikawa.fetchHanakoganeiDirection(date) } }
                    val keioResult = keioDeferred.await()
                    val tachikawaResult = tachikawaDeferred.await()
                    BusTimetableDay(
                        date = date,
                        direction = direction,
                        trips = (keioResult.getOrDefault(emptyList()) + tachikawaResult.getOrDefault(emptyList()))
                            .sortedBy { it.departureAt },
                        failures = buildMap {
                            if (keioResult.isFailure) put(BusCompany.Keio, true)
                            if (tachikawaResult.isFailure) put(BusCompany.Tachikawa, true)
                        },
                    )
                }
                BusDirection.HitotsubashiKodaira -> {
                    val gingaDeferred = async { catchCompany { ginga.fetchHitotsubashiDirection(date) } }
                    val seibuDeferred = async { catchCompany { seibu.fetchHitotsubashiDirection(date) } }
                    val gingaResult = gingaDeferred.await()
                    val seibuResult = seibuDeferred.await()
                    BusTimetableDay(
                        date = date,
                        direction = direction,
                        trips = (gingaResult.getOrDefault(emptyList()) + seibuResult.getOrDefault(emptyList()))
                            .sortedBy { it.departureAt },
                        failures = buildMap {
                            if (gingaResult.isFailure) put(BusCompany.Ginga, true)
                            if (seibuResult.isFailure) put(BusCompany.Seibu, true)
                        },
                    )
                }
                BusDirection.KokubunjiDepart -> {
                    val keioDeferred = async { catchCompany { keio.fetchKokubunjiDepart(date) } }
                    val tachikawaDeferred = async { catchCompany { tachikawa.fetchKokubunjiDepart(date) } }
                    val seibuDeferred = async { catchCompany { seibu.fetchKokubunjiDepart(date) } }
                    val bunbusDeferred = async { catchCompany { bunbus.fetchKokubunjiDepart(date) } }
                    val limoDeferred = async { catchCompany { limousine.fetchKokubunjiDepart(date) } }
                    val keioResult = keioDeferred.await()
                    val tachikawaResult = tachikawaDeferred.await()
                    val seibuResult = seibuDeferred.await()
                    val bunbusResult = bunbusDeferred.await()
                    val limoResult = limoDeferred.await()
                    BusTimetableDay(
                        date = date,
                        direction = direction,
                        trips = (
                            keioResult.getOrDefault(emptyList()) +
                                tachikawaResult.getOrDefault(emptyList()) +
                                seibuResult.getOrDefault(emptyList()) +
                                bunbusResult.getOrDefault(emptyList()) +
                                limoResult.getOrDefault(emptyList())
                            ).sortedBy { it.departureAt },
                        failures = buildMap {
                            if (keioResult.isFailure) put(BusCompany.Keio, true)
                            if (tachikawaResult.isFailure) put(BusCompany.Tachikawa, true)
                            if (seibuResult.isFailure) put(BusCompany.Seibu, true)
                            if (bunbusResult.isFailure) put(BusCompany.Community, true)
                            if (limoResult.isFailure) put(BusCompany.TokyoAirportTransport, true)
                        },
                    )
                }
                BusDirection.MusashiKoganeiDepart -> {
                    val keioDeferred = async { catchCompany { keio.fetchMusashiKoganeiDepart(date) } }
                    val seibuDeferred = async { catchCompany { seibu.fetchMusashiKoganeiDepart(date) } }
                    val kantoDeferred = async { catchCompany { kanto.fetchMusashiKoganeiDepart(date) } }
                    val cocoDeferred = async { catchCompany { cocobus.fetchMusashiKoganeiDepart(date) } }
                    val limoDeferred = async { catchCompany { limousine.fetchMusashiKoganeiDepart(date) } }
                    val keioResult = keioDeferred.await()
                    val seibuResult = seibuDeferred.await()
                    val kantoResult = kantoDeferred.await()
                    val cocoResult = cocoDeferred.await()
                    val limoResult = limoDeferred.await()
                    BusTimetableDay(
                        date = date,
                        direction = direction,
                        trips = (
                            keioResult.getOrDefault(emptyList()) +
                                seibuResult.getOrDefault(emptyList()) +
                                kantoResult.getOrDefault(emptyList()) +
                                cocoResult.getOrDefault(emptyList()) +
                                limoResult.getOrDefault(emptyList())
                            ).sortedBy { it.departureAt },
                        failures = buildMap {
                            if (keioResult.isFailure) put(BusCompany.Keio, true)
                            if (seibuResult.isFailure) put(BusCompany.Seibu, true)
                            if (kantoResult.isFailure) put(BusCompany.Kanto, true)
                            if (cocoResult.isFailure) put(BusCompany.KoganeiCommunity, true)
                            if (limoResult.isFailure) put(BusCompany.TokyoAirportTransport, true)
                        },
                    )
                }
                BusDirection.ChuoDoFuchuDepart -> {
                    val result = catchCompany { bushikaku.fetchChuoDoFuchuDepart(date) }
                    BusTimetableDay(
                        date = date,
                        direction = direction,
                        trips = result.getOrDefault(emptyList()).sortedBy { it.departureAt },
                        failures = buildMap {
                            if (result.isFailure) put(BusCompany.Highway, true)
                        },
                    )
                }
            }
        }

    private data class CacheKey(
        val date: LocalDate,
        val direction: BusDirection,
    )

    companion object {
        /**
         * 会社単位の取得。
         * 通信そのもののやり直しは [app.smartdisplay.util.NetworkLog] が最大3回行う。
         * キャンセルは失敗にせず上位へ伝播する。
         */
        private suspend fun <T> catchCompany(block: suspend () -> T): Result<T> {
            return try {
                Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
    }
}

data class BusTimetableDay(
    val date: LocalDate,
    val direction: BusDirection,
    val trips: List<BusTrip>,
    val failures: Map<BusCompany, Boolean> = emptyMap(),
) {
    val anyFailed: Boolean get() = failures.values.any { it }
}
