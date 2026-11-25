package app.smartdisplay.bus

import app.smartdisplay.util.NetworkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 京王バスナビ（Navitime）の時刻表 HTML から、マルバーン・カレッジ東京発を取得する。
 *
 * Android UA だと smart 版へリダイレクトされるため、PC UA で取得しつつ
 * PC / スマホ両方の HTML をパースできるようにしている。
 */
class KeioBusClient {
    suspend fun fetchKokubunjiDirection(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        val kind = busScheduleKind(date)
        KokubunjiRoutes.flatMap { route -> fetchRouteTrips(route, date, kind) }
    }

    suspend fun fetchHanakoganeiDirection(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        val kind = busScheduleKind(date)
        HanakoganeiRoutes.flatMap { route -> fetchRouteTrips(route, date, kind) }
    }

    /** 国分寺駅北口・南口発（bus-navi + 公開ダイヤ）。 */
    suspend fun fetchKokubunjiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        val kind = busScheduleKind(date)
        val busNaviResults = KokubunjiDepartBusNaviRoutes.map { route ->
            runCatching { fetchRouteTrips(route, date, kind) }
        }
        val publicResult = runCatching {
            fetchPublicDepartRoutes(KokubunjiDepartRoutes, date, BusDirection.KokubunjiDepart)
        }
        val trips = (
            busNaviResults.flatMap { it.getOrDefault(emptyList()) } +
                publicResult.getOrDefault(emptyList())
            )
            .distinctBy { "${it.originStop}|${it.routeName}|${it.destinationLabel}|${it.departureAt}" }
            .sortedBy { it.departureAt }
        if (trips.isEmpty()) {
            val firstError = (
                busNaviResults.mapNotNull { it.exceptionOrNull() } +
                    listOfNotNull(publicResult.exceptionOrNull())
                ).firstOrNull()
            if (firstError != null) {
                throw firstError
            }
        }
        trips
    }

    /** 武蔵小金井駅北口・南口発（NAVITIME 公開ダイヤ）。 */
    suspend fun fetchMusashiKoganeiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        fetchPublicDepartRoutes(
            MusashiKoganeiDepartRoutes,
            date,
            BusDirection.MusashiKoganeiDepart,
        )
    }

    private fun fetchPublicDepartRoutes(
        routes: List<NavitimePublicRoute>,
        date: LocalDate,
        direction: BusDirection,
    ): List<BusTrip> = fetchNavitimePublicRoutes(
        routes = routes,
        date = date,
        company = BusCompany.Keio,
        direction = direction,
        idPrefix = "keio-depart",
        emptyErrorMessage = { route ->
            "京王バス時刻が空です (${route.originStop} ${route.routeName})"
        },
        normalizeRouteName = true,
    )

    private fun fetchRouteTrips(route: KeioRoute, date: LocalDate, kind: BusScheduleKind): List<BusTrip> {
        val html = download(
            "$DiagramBase?orvCode=${route.orvCode}&course=${route.primaryCourseId}&stopNo=${route.stopNo}&date=$date",
        )
        val departures = parseDiagramDepartures(html, kind)
            .filter { it.courseId in route.courseIds }
            .distinctBy { it.time }
            .sortedBy { it.time }
        if (departures.isEmpty()) {
            return emptyList()
        }
        val travel = travelMinutes(route, departures.first())
        return departures.map { dep ->
            val departureAt = date.atBusTime(dep.time)
            val arrivalAt = departureAt.plusMinutes(travel)
            val viaArrivals = when {
                route.viaOffsets.isNotEmpty() -> viaArrivalsFromOffsets(departureAt, route.viaOffsets)
                route.viaFilter != null -> {
                    mapOf(
                        route.viaFilter to BusViaArrival(
                            at = arrivalAt,
                            stopLabel = route.arrivalStopLabel,
                        ),
                    )
                }
                else -> emptyMap()
            }
            BusTrip(
                id = "keio-${route.primaryCourseId}-$departureAt",
                company = BusCompany.Keio,
                routeName = normalizeBusRouteName(route.routeName),
                direction = route.direction,
                destinationLabel = route.destinationLabel,
                speechDestinationLabel = route.speechDestinationLabel,
                originStop = route.originStop,
                departureAt = departureAt,
                arrivalAt = if (route.showArrivalByDefault) arrivalAt else null,
                arrivalStopLabel = if (route.showArrivalByDefault) route.arrivalStopLabel else null,
                arrivalNote = route.arrivalNote,
                viaArrivals = viaArrivals,
                stopRef = stopListUrl(route, dep)?.let { BusStopRef.Keio(it) },
            )
        }
    }

    /** 系統時刻表ページから停車停留所を読む。 */
    fun fetchStopList(url: String): List<BusStop> {
        val parsed = parseRouteStops(download(url))
        if (parsed.isEmpty()) {
            error("停車停留所がありません")
        }
        val out = mutableListOf<BusStop>()
        for (stop in parsed) {
            val next = if (stop.departing) {
                BusStop(name = stop.name, departure = stop.time)
            } else {
                BusStop(name = stop.name, arrival = stop.time)
            }
            val previous = out.lastOrNull()
            if (previous != null && previous.name == next.name) {
                out[out.lastIndex] = previous.copy(
                    arrival = previous.arrival ?: next.arrival,
                    departure = previous.departure ?: next.departure,
                )
            } else {
                out += next
            }
        }
        return out
    }

    private fun stopListUrl(route: KeioRoute, sample: DiagramDeparture): String? {
        val query = when {
            sample.operation.isNotBlank() -> listOf(
                "operation" to sample.operation,
                "datetime" to sample.datetime,
                "node" to route.orvCode,
                "course" to sample.courseId,
                "stop-no" to sample.stopNo,
            ) to RouteTimetableBase
            sample.tCode.isNotBlank() -> listOf(
                "tCode" to sample.tCode,
                "datetime" to sample.datetime,
                "posType" to sample.stopNo,
                "node" to route.orvCode,
                "course" to sample.courseId,
                "stopNo" to sample.stopNo,
            ) to StopListDiagramBase
            else -> return null
        }
        val (params, base) = query
        val qs = params.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$base?$qs"
    }

    private fun travelMinutes(route: KeioRoute, sample: DiagramDeparture): Long {
        return runCatching {
            val stopsHtml = stopListUrl(route, sample)?.let(::download)
                ?: return@runCatching route.fallbackTravelMinutes
            val stops = parseRouteStops(stopsHtml)
            val board = stops.firstOrNull { stop ->
                stop.name.contains(route.originStop) ||
                    route.originStop.contains(stop.name) ||
                    stop.name.contains("マルバーン")
            } ?: return@runCatching route.fallbackTravelMinutes
            val arrive = stops.firstOrNull { stop ->
                route.arrivalMatch.any { stop.name.contains(it) }
            } ?: return@runCatching route.fallbackTravelMinutes
            val day = LocalDate.parse(sample.datetime.take(10))
            Duration.between(
                LocalDateTime.of(day, board.time),
                LocalDateTime.of(day, arrive.time),
            ).toMinutes().coerceAtLeast(1)
        }.getOrDefault(route.fallbackTravelMinutes)
    }

    private fun parseDiagramDepartures(html: String, kind: BusScheduleKind): List<DiagramDeparture> {
        val pc = parsePcDiagram(html, kind)
        if (pc.isNotEmpty()) {
            return pc
        }
        return parseSmartDiagram(html, kind)
    }

    private fun parsePcDiagram(html: String, kind: BusScheduleKind): List<DiagramDeparture> {
        val kindToken = when (kind) {
            BusScheduleKind.Weekday -> "diagram-date-weekday"
            BusScheduleKind.Saturday -> "diagram-date-saturday"
            BusScheduleKind.Holiday -> "diagram-date-holiday"
        }
        val out = mutableListOf<DiagramDeparture>()
        val hourBlocks = Regex(
            """<th class="hour">(.*?)</th>(.*?)(?=<th class="hour">|</table>)""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html)
        for (block in hourBlocks) {
            val tds = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(block.groupValues[2])
                .map { it.groupValues[1] }
            for (td in tds) {
                if (!td.contains(kindToken)) {
                    continue
                }
                for (m in Regex("""BusRouteTimetable\?([^"']+)""").findAll(td)) {
                    parseDepartureParams(m.groupValues[1])?.let { out += it }
                }
            }
        }
        return out
    }

    private fun parseSmartDiagram(html: String, kind: BusScheduleKind): List<DiagramDeparture> {
        val titleClass = when (kind) {
            BusScheduleKind.Weekday -> "WEEKDAY"
            BusScheduleKind.Saturday -> "SATURDAY"
            BusScheduleKind.Holiday -> "SUNDAY"
        }
        val out = mutableListOf<DiagramDeparture>()
        val loose = Regex(
            """hour-title\s+$titleClass[^<]*</div>\s*<div class="minutes-area">(.*?)</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html)
        for (block in loose) {
            for (m in Regex("""StopListDiagram\?([^"']+)""").findAll(block.groupValues[1])) {
                parseDepartureParams(m.groupValues[1])?.let { out += it }
            }
        }
        return out
    }

    private fun parseDepartureParams(rawQuery: String): DiagramDeparture? {
        val params = parseQuery(rawQuery)
        val datetime = params["datetime"] ?: return null
        val courseId = params["course"] ?: return null
        val time = runCatching {
            LocalDateTime.parse(datetime, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalTime()
        }.getOrNull() ?: return null
        return DiagramDeparture(
            time = time,
            datetime = datetime,
            courseId = courseId,
            operation = params["operation"].orEmpty(),
            tCode = params["tCode"].orEmpty(),
            stopNo = params["stop-no"] ?: params["stopNo"] ?: params["posType"] ?: "4",
        )
    }

    private fun parseRouteStops(html: String): List<RouteStop> {
        val start = html.indexOf("""id="railroad-matrix"""")
            .takeIf { it >= 0 }
            ?: html.indexOf("railroad-matrix")
        if (start < 0) {
            return parseSmartStops(html)
        }
        val chunk = html.substring(start, (start + 30_000).coerceAtMost(html.length))
            .replace(Regex("""<script.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<br\s*/?>"""), "\n")
            .replace("</div>", "\n")
            .replace(Regex("""<[^>]+>"""), "")
        return extractStopsFromPlainText(chunk)
    }

    private fun parseSmartStops(html: String): List<RouteStop> {
        val text = html
            .replace(Regex("""<script.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<br\s*/?>"""), "\n")
            .replace("</div>", "\n")
            .replace(Regex("""<[^>]+>"""), "")
        return extractStopsFromPlainText(text)
    }

    private fun extractStopsFromPlainText(chunk: String): List<RouteStop> {
        val lines = chunk.lineSequence()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("id=") &&
                    "ページ" !in it &&
                    "Copyright" !in it &&
                    "ご利用" !in it &&
                    "[時刻表]" !in it
            }
            .toList()
        val stops = mutableListOf<RouteStop>()
        var i = 0
        while (i < lines.size) {
            val m = Regex("""^(\d{1,2}:\d{2})(発|着)$""").find(lines[i])
            if (m != null) {
                val time = LocalTime.parse(m.groupValues[1], MinuteFormatter)
                var j = i + 1
                while (j < lines.size && (lines[j].matches(Regex("""^\d+じ.*""")) || "ふん" in lines[j])) {
                    j++
                }
                val name = lines.getOrNull(j).orEmpty()
                if (name.isNotBlank() && !name.first().isDigit()) {
                    stops += RouteStop(
                        name = name,
                        time = time,
                        departing = m.groupValues[2] == "発",
                    )
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return stops
    }

    private fun download(url: String): String = NetworkLog.trace(url) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            )
            setRequestProperty("Accept-Language", "ja,en;q=0.8")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: error("Keio bus HTTP $code")
            }
            BufferedReader(InputStreamReader(stream, Charset.forName("UTF-8"))).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val decoded = URLDecoder.decode(raw, "UTF-8")
        return decoded.split("&").mapNotNull { part ->
            val idx = part.indexOf("=")
            if (idx <= 0) {
                null
            } else {
                part.substring(0, idx) to part.substring(idx + 1)
            }
        }.toMap()
    }

    private data class DiagramDeparture(
        val time: LocalTime,
        val datetime: String,
        val courseId: String,
        val operation: String,
        val tCode: String = "",
        val stopNo: String,
    )

    private data class RouteStop(
        val name: String,
        val time: LocalTime,
        val departing: Boolean,
    )

    private data class KeioRoute(
        val primaryCourseId: String,
        val stopNo: String,
        val routeName: String,
        val direction: BusDirection,
        val destinationLabel: String,
        val speechDestinationLabel: String = destinationLabel,
        val viaFilter: BusViaFilter? = null,
        val showArrivalByDefault: Boolean = false,
        val courseIds: Set<String> = setOf(primaryCourseId),
        val arrivalMatch: List<String>,
        val arrivalStopLabel: String,
        val arrivalNote: String? = null,
        val fallbackTravelMinutes: Long,
        val orvCode: String = MalvernStopId,
        val originStop: String = MalvernStopName,
        val viaOffsets: List<BusViaOffset> = emptyList(),
    )

    companion object {
        private const val DiagramBase =
            "https://transfer.navitime.biz/bus-navi/pc/diagram/BusDiagram"
        private const val RouteTimetableBase =
            "https://transfer.navitime.biz/bus-navi/pc/diagram/BusRouteTimetable"
        private const val StopListDiagramBase =
            "https://transfer.navitime.biz/bus-navi/smart/diagram/StopListDiagram"
        private const val MalvernStopId = "00049570"
        private const val MalvernStopName = "マルバーン・カレッジ東京"
        private val MinuteFormatter = DateTimeFormatter.ofPattern("H:mm")

        private val KokubunjiRoutes = listOf(
            KeioRoute(
                primaryCourseId = "0000457184", stopNo = "4", routeName = "寺86",
                direction = BusDirection.KokubunjiMusashiKoganei, destinationLabel = "国分寺駅北口",
                viaFilter = BusViaFilter.KokubunjiNorth, courseIds = setOf("0000457184", "0000457182"),
                arrivalMatch = listOf("国分寺駅北口"), arrivalStopLabel = "国分寺駅北口", fallbackTravelMinutes = 7,
            ),
            KeioRoute(
                primaryCourseId = "0000457016", stopNo = "4", routeName = "寺87",
                direction = BusDirection.KokubunjiMusashiKoganei, destinationLabel = "総合医療センター",
                viaFilter = BusViaFilter.KokubunjiSouth, arrivalMatch = listOf("国分寺駅南口"), arrivalStopLabel = "国分寺駅南口",
                arrivalNote = "※国分寺駅北口は通過", fallbackTravelMinutes = 8,
            ),
            KeioRoute(
                primaryCourseId = "0000456756", stopNo = "4", routeName = "武41",
                direction = BusDirection.KokubunjiMusashiKoganei, destinationLabel = "武蔵小金井駅北口",
                viaFilter = BusViaFilter.MusashiKoganeiNorth, courseIds = setOf("0000456756", "0000456728"),
                arrivalMatch = listOf("武蔵小金井駅北口"), arrivalStopLabel = "武蔵小金井駅北口", fallbackTravelMinutes = 12,
            ),
        )

        /** 花小金井・昭和病院方面（小平団地方向）。経由地フィルタには通常ヒットしない。 */
        private val HanakoganeiRoutes = listOf(
            KeioRoute(
                primaryCourseId = "0000457332", stopNo = "6", routeName = "寺86",
                direction = BusDirection.HanakoganeiShowa, destinationLabel = "小平団地",
                courseIds = setOf("0000457332", "0000457330"), arrivalMatch = listOf("小平団地"), arrivalStopLabel = "小平団地",
                fallbackTravelMinutes = 6,
            ),
            KeioRoute(
                primaryCourseId = "0000457157", stopNo = "12", routeName = "寺87",
                direction = BusDirection.HanakoganeiShowa, destinationLabel = "小平団地",
                courseIds = setOf("0000457157", "0000457329"), arrivalMatch = listOf("小平団地"), arrivalStopLabel = "小平団地",
                fallbackTravelMinutes = 6,
            ),
            KeioRoute(
                primaryCourseId = "0000457333", stopNo = "10", routeName = "武41",
                direction = BusDirection.HanakoganeiShowa, destinationLabel = "小平団地", arrivalMatch = listOf("小平団地"),
                arrivalStopLabel = "小平団地", fallbackTravelMinutes = 6,
            ),
        )

        /** bus-navi で確実に取れる国分寺駅発（公開ダイヤ失敗時の保険）。 */
        private val KokubunjiDepartBusNaviRoutes = listOf(
            KeioRoute(
                primaryCourseId = "0000457332", stopNo = "1", routeName = "寺86",
                direction = BusDirection.KokubunjiDepart, destinationLabel = "小平団地",
                viaFilter = BusViaFilter.DepartMalvern, courseIds = setOf("0000457332", "0000457330"),
                arrivalMatch = listOf("マルバーン・カレッジ東京", "マルバーン"), arrivalStopLabel = "マルバーン・カレッジ東京",
                fallbackTravelMinutes = 5, orvCode = "00021536", originStop = "国分寺駅北口",
                viaOffsets = listOf(BusViaOffset(BusViaFilter.DepartMalvern, "マルバーン・カレッジ東京", 5)),
            ),
            KeioRoute(
                primaryCourseId = "0000457186", stopNo = "1", routeName = "武42",
                direction = BusDirection.KokubunjiDepart, destinationLabel = "武蔵小金井駅北口",
                viaFilter = BusViaFilter.DepartMusashiKoganei, arrivalMatch = listOf("武蔵小金井駅北口"),
                arrivalStopLabel = "武蔵小金井駅北口", fallbackTravelMinutes = 11, orvCode = "00021536", originStop = "国分寺駅北口",
                viaOffsets = listOf(BusViaOffset(BusViaFilter.DepartMusashiKoganei, "武蔵小金井駅北口", 11)),
            ),
        )

        private val KokubunjiDepartRoutes = listOf(
            NavitimePublicRoute(
                nodeId = "00021536", lineId = "00006403",
                // /0/ は http の /1/ へ 301 されるため、最初から https の正規 URL を使う
                directionIndex = 1, routeName = "寺85/86/87/88", originStop = "国分寺駅北口", destinationLabel = "小平団地",
                vias = listOf(BusViaOffset(BusViaFilter.DepartMalvern, "マルバーン・カレッジ東京", 5)),
            ),
            NavitimePublicRoute(
                nodeId = "00021536", lineId = "00006560", directionIndex = 0, routeName = "武42", originStop = "国分寺駅北口",
                destinationLabel = "武蔵小金井駅北口",
                vias = listOf(BusViaOffset(BusViaFilter.DepartMusashiKoganei, "武蔵小金井駅北口", 11)),
            ),
            NavitimePublicRoute(
                nodeId = "00020352", lineId = "00006403", directionIndex = 0, routeName = "寺85/86/87/88",
                originStop = "国分寺駅南口", destinationLabel = "総合医療センター",
                vias = listOf(BusViaOffset(BusViaFilter.DepartMedicalCenter, "総合医療センター", 13)),
            ),
            NavitimePublicRoute(
                nodeId = "00020352", lineId = "00006403", directionIndex = 1, routeName = "寺85/86/87/88",
                originStop = "国分寺駅南口", destinationLabel = "小平団地",
                vias = listOf(BusViaOffset(BusViaFilter.DepartMalvern, "マルバーン・カレッジ東京", 6)),
            ),
            NavitimePublicRoute(
                nodeId = "00020352", lineId = "00006404",
                // /0/ は http://…/1/ へ 301 され Android で失敗するため /1/ を直接使う
                directionIndex = 1, routeName = "寺91", originStop = "国分寺駅南口", destinationLabel = "府中駅",
                vias = listOf(BusViaOffset(BusViaFilter.DepartFuchu, "府中駅", 16)),
                // 明印は明星学苑止まり。無印のみ府中駅まで
                excludeArriveMarks = setOf("明"),
            ),
            NavitimePublicRoute(
                nodeId = "00020352", lineId = "00006402", directionIndex = 0, routeName = "寺83", originStop = "国分寺駅南口",
                destinationLabel = "東芝前", speechDestinationLabel = "東芝前、北府中駅前",
                vias = listOf(BusViaOffset(BusViaFilter.DepartKitaFuchu, "東芝前", 12)),
            ),
        )

        private val MusashiKoganeiDepartRoutes = listOf(
            NavitimePublicRoute(
                nodeId = "00021230", lineId = "00006560", directionIndex = 1, routeName = "武42",
                originStop = "武蔵小金井駅北口", destinationLabel = "国分寺駅北口",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartKokubunji, "国分寺駅北口", 11)),
            ),
            NavitimePublicRoute(
                nodeId = "00021230", lineId = "00006556", directionIndex = 1, routeName = "武41",
                originStop = "武蔵小金井駅北口", destinationLabel = "小平団地",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartMalvern, "マルバーン・カレッジ東京", 8)),
            ),
            NavitimePublicRoute(
                nodeId = "00021230", lineId = "00006793", directionIndex = 1, routeName = "武03/04",
                originStop = "武蔵小金井駅北口", destinationLabel = "東小金井駅",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartHigashiKoganei, "東小金井駅", 10)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006559", directionIndex = 0, routeName = "武71",
                originStop = "武蔵小金井駅南口", destinationLabel = "府中駅",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartFuchu, "府中駅", 20)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006562", directionIndex = 0, routeName = "武73",
                originStop = "武蔵小金井駅南口", destinationLabel = "府中駅",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartFuchu, "府中駅", 22)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006648", directionIndex = 1, routeName = "武66",
                originStop = "武蔵小金井駅南口", destinationLabel = "東芝前", speechDestinationLabel = "東芝前、北府中駅前",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartKitaFuchu, "東芝前", 15)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006551", directionIndex = 0, routeName = "府75",
                originStop = "武蔵小金井駅南口", destinationLabel = "東府中駅",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartHigashiFuchu, "東府中駅", 18)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006568", directionIndex = 1, routeName = "武91",
                originStop = "武蔵小金井駅南口", destinationLabel = "調布駅北口",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartChofu, "調布駅北口", 28)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006650", directionIndex = 1, routeName = "武93",
                originStop = "武蔵小金井駅南口", destinationLabel = "調布駅北口",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartChofu, "調布駅北口", 30)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006687", directionIndex = 1, routeName = "武94/95",
                originStop = "武蔵小金井駅南口", destinationLabel = "多磨霊園駅",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartTamaReien, "多磨霊園駅", 22)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006684", directionIndex = 1, routeName = "武84/85",
                originStop = "武蔵小金井駅南口", destinationLabel = "多磨町",
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006334", directionIndex = 1, routeName = "境81",
                originStop = "武蔵小金井駅南口", destinationLabel = "武蔵境駅南口",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartMusashiSakai, "武蔵境駅南口", 15)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006276", directionIndex = 1, routeName = "境96",
                originStop = "武蔵小金井駅南口", destinationLabel = "武蔵境駅南口",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartMusashiSakai, "武蔵境駅南口", 18)),
            ),
        )
    }
}
