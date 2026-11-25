package app.smartdisplay.bus

import java.time.LocalDate
import java.time.LocalDateTime

/** 発車からの経過分で経由地着を表す。 */
data class BusViaOffset(
    val filter: BusViaFilter,
    val stopLabel: String,
    val minutes: Long,
)

fun viaArrivalsFromOffsets(
    departureAt: LocalDateTime,
    offsets: List<BusViaOffset>,
): Map<BusViaFilter, BusViaArrival> {
    if (offsets.isEmpty()) {
        return emptyMap()
    }
    val out = linkedMapOf<BusViaFilter, BusViaArrival>()
    for ((filter, stop, minutes) in offsets) {
        out[filter] = BusViaArrival(
            at = departureAt.plusMinutes(minutes),
            stopLabel = stop,
        )
    }
    return out
}

fun defaultBusTripDistinctKey(trip: BusTrip): String =
    "${trip.originStop}|${trip.routeName}|${trip.destinationLabel}|${trip.departureAt}"

/**
 * 複数ルートの [Result] を結合する。
 * すべて空で失敗があるときだけ、最初の例外を投げる。
 */
fun mergeBusTripResults(
    results: List<Result<List<BusTrip>>>,
    distinctKey: (BusTrip) -> String = ::defaultBusTripDistinctKey,
): List<BusTrip> {
    val trips = results.flatMap { it.getOrDefault(emptyList()) }
        .distinctBy(distinctKey)
        .sortedBy { it.departureAt }
    if (trips.isEmpty()) {
        val firstError = results.firstOrNull { it.isFailure }?.exceptionOrNull()
        if (firstError != null) {
            throw firstError
        }
    }
    return trips
}

/**
 * NAVITIME 公開ダイヤの1系統を [BusTrip] 一覧にする。
 * [excludeArriveMarks] が空でなければ印付き便を除外する（京王向け）。
 */
fun fetchNavitimePublicRouteTrips(
    nodeId: String,
    lineId: String,
    directionIndex: Int,
    date: LocalDate,
    kind: BusScheduleKind,
    company: BusCompany,
    direction: BusDirection,
    routeName: String,
    originStop: String,
    destinationLabel: String,
    speechDestinationLabel: String,
    vias: List<BusViaOffset>,
    idPrefix: String,
    emptyErrorMessage: String,
    normalizeRouteName: Boolean = false,
    excludeArriveMarks: Collection<String> = emptyList(),
): List<BusTrip> {
    val html = NavitimePublicDiagram.download(
        NavitimePublicDiagram.diagramUrl(nodeId, lineId, directionIndex),
    )
    val departures = NavitimePublicDiagram.parseDepartureEntries(html, kind)
        .asSequence()
        .filter { entry ->
            excludeArriveMarks.none { mark -> entry.arriveMark.contains(mark) }
        }
        .distinctBy { it.time }
        .sortedBy { it.time }
        .toList()
    if (departures.isEmpty()) {
        error(emptyErrorMessage)
    }
    val displayRouteName = if (normalizeRouteName) {
        normalizeBusRouteName(routeName)
    } else {
        routeName
    }
    return departures.map { entry ->
        val departureAt = date.atBusTime(entry.time)
        BusTrip(
            id = "$idPrefix-$lineId-$directionIndex-$departureAt",
            company = company,
            routeName = displayRouteName,
            direction = direction,
            destinationLabel = destinationLabel,
            speechDestinationLabel = speechDestinationLabel,
            originStop = originStop,
            departureAt = departureAt,
            viaArrivals = viaArrivalsFromOffsets(departureAt, vias),
            stopRef = entry.stopsUrl.takeIf { it.isNotEmpty() }?.let { BusStopRef.Navitime(it) },
        )
    }
}

/** NAVITIME 公開ダイヤの系統。会社ごとの取得で同じ形を使う。 */
internal data class NavitimePublicRoute(
    val nodeId: String,
    val lineId: String,
    val directionIndex: Int,
    val routeName: String,
    val originStop: String,
    val destinationLabel: String,
    val speechDestinationLabel: String = destinationLabel,
    val vias: List<BusViaOffset> = emptyList(),
    val busDirection: BusDirection? = null,
    val excludeArriveMarks: Set<String> = emptySet(),
)

/** 複数系統をまとめて取得する。空で失敗があるときだけ最初の例外を投げる。 */
internal fun fetchNavitimePublicRoutes(
    routes: List<NavitimePublicRoute>,
    date: LocalDate,
    company: BusCompany,
    direction: BusDirection,
    idPrefix: String,
    emptyErrorMessage: (NavitimePublicRoute) -> String,
    normalizeRouteName: Boolean = false,
    distinctKey: (BusTrip) -> String = ::defaultBusTripDistinctKey,
): List<BusTrip> {
    val kind = busScheduleKind(date)
    return mergeBusTripResults(
        results = routes.map { route ->
            runCatching {
                fetchNavitimePublicRouteTrips(
                    nodeId = route.nodeId,
                    lineId = route.lineId,
                    directionIndex = route.directionIndex,
                    date = date,
                    kind = kind,
                    company = company,
                    direction = route.busDirection ?: direction,
                    routeName = route.routeName,
                    originStop = route.originStop,
                    destinationLabel = route.destinationLabel,
                    speechDestinationLabel = route.speechDestinationLabel,
                    vias = route.vias,
                    idPrefix = idPrefix,
                    emptyErrorMessage = emptyErrorMessage(route),
                    normalizeRouteName = normalizeRouteName,
                    excludeArriveMarks = route.excludeArriveMarks,
                )
            }
        },
        distinctKey = distinctKey,
    )
}
