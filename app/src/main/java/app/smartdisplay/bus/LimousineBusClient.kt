package app.smartdisplay.bus

import app.smartdisplay.util.NetworkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime

/**
 * 東京空港交通サイトの時刻表（国分寺・府中・武蔵小金井・調布→羽田）から
 * 国分寺駅／武蔵小金井駅発のリムジンを取得する。運行は京王バスと東京空港交通。
 */
class LimousineBusClient {
    suspend fun fetchKokubunjiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        fetchDepartures(
            date = date,
            originStop = OriginKokubunji,
            direction = BusDirection.KokubunjiDepart,
            viaFilters = KokubunjiHanedaVias,
        )
    }

    suspend fun fetchMusashiKoganeiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        fetchDepartures(
            date = date,
            originStop = OriginMusashiKoganei,
            direction = BusDirection.MusashiKoganeiDepart,
            viaFilters = MusashiKoganeiHanedaVias,
        )
    }

    private fun fetchDepartures(
        date: LocalDate,
        originStop: String,
        direction: BusDirection,
        viaFilters: List<Pair<BusViaFilter, String>>,
    ): List<BusTrip> {
        val root = downloadTimetableRoot()
        val tables = root.optJSONArray("displayTimeTables")
            ?: error("リムジン時刻表が空です")
        val trips = mutableListOf<BusTrip>()
        for (tableIndex in 0 until tables.length()) {
            val table = tables.optJSONObject(tableIndex) ?: continue
            trips += parseTable(
                table = table,
                date = date,
                originStop = originStop,
                direction = direction,
                viaFilters = viaFilters,
            )
        }
        return trips
            .distinctBy { "${it.company}|${it.originStop}|${it.routeName}|${it.destinationLabel}|${it.departureAt}" }
            .sortedBy { it.departureAt }
    }

    private fun parseTable(
        table: JSONObject,
        date: LocalDate,
        originStop: String,
        direction: BusDirection,
        viaFilters: List<Pair<BusViaFilter, String>>,
    ): List<BusTrip> {
        val stations = table.optJSONArray("stations") ?: return emptyList()
        val trains = table.optJSONArray("trains") ?: return emptyList()
        val stationNames = buildList {
            for (i in 0 until stations.length()) {
                val name = stations.optJSONObject(i)?.optString("name").orEmpty()
                add(name)
            }
        }
        val originIndex = stationNames.indexOf(originStop)
        if (originIndex < 0) {
            return emptyList()
        }
        val viaIndices = viaFilters.mapNotNull { (filter, stopName) ->
            val index = stationNames.indexOf(stopName)
            if (index >= 0) Triple(filter, stopName, index) else null
        }
        val out = mutableListOf<BusTrip>()
        for (trainIndex in 0 until trains.length()) {
            val train = trains.optJSONObject(trainIndex) ?: continue
            val times = train.optJSONArray("time") ?: continue
            val originTime = times.optJSONObject(originIndex) ?: continue
            val departureTime = parseDiagramTime(originTime.opt("departureTime")) ?: continue
            val company = resolveCompany(train.optJSONArray("companyIds")) ?: continue
            val departureAt = date.atBusTime(departureTime)
            val destinationLabel = train.optString("to").ifBlank { TerminalT3 }
            val routeName = normalizeBusRouteName(
                train.optString("rosen").ifBlank { train.optString("lineName") }.ifBlank { "羽田空港線" },
            )
            val viaArrivals = linkedMapOf<BusViaFilter, BusViaArrival>()
            for ((filter, stopName, index) in viaIndices) {
                val cell = times.optJSONObject(index) ?: continue
                val arrivalTime = parseDiagramTime(cell.opt("arrivalTime"))
                    ?: parseDiagramTime(cell.opt("departureTime"))
                    ?: continue
                viaArrivals[filter] = BusViaArrival(
                    at = date.atBusTime(arrivalTime),
                    stopLabel = stopName,
                )
            }
            if (viaArrivals.isEmpty()) {
                continue
            }
            val stops = buildList {
                for (index in stationNames.indices) {
                    val name = stationNames[index]
                    if (name.isBlank()) {
                        continue
                    }
                    val cell = times.optJSONObject(index) ?: continue
                    val arrival = parseDiagramTime(cell.opt("arrivalTime"))
                    val departure = parseDiagramTime(cell.opt("departureTime"))
                    if (arrival == null && departure == null) {
                        continue
                    }
                    add(
                        BusStop(
                            name = name,
                            arrival = arrival,
                            departure = departure,
                        ),
                    )
                }
            }
            val uniqueId = train.optString("uniqueId").ifBlank { "$trainIndex" }
            out += BusTrip(
                id = "limousine-$uniqueId-$originStop-$departureAt",
                company = company,
                routeName = routeName,
                direction = direction,
                destinationLabel = destinationLabel,
                speechDestinationLabel = destinationLabel,
                originStop = originStop,
                departureAt = departureAt,
                viaArrivals = viaArrivals,
                stopRef = stops.takeIf { it.isNotEmpty() }?.let { BusStopRef.Embedded(it) },
            )
        }
        return out
    }

    private fun resolveCompany(companyIds: JSONArray?): BusCompany? {
        if (companyIds == null) {
            return null
        }
        for (i in 0 until companyIds.length()) {
            CompanyById[companyIds.optString(i)]?.let { return it }
        }
        return null
    }

    private fun downloadTimetableRoot(): JSONObject {
        val payload = NetworkLog.trace(DataUrl) {
            val connection = (URL(DataUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", DesktopUserAgent)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "ja,en;q=0.8")
                setRequestProperty("Referer", PageUrl)
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: error("リムジン時刻表 HTTP $code")
                }
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        return extractDisplayRoot(payload)
    }

    /**
     * SvelteKit の devalue 形式（`nodes[n].data` が参照配列）から
     * `displayTimeTables` を含むルートオブジェクトを復元する。
     */
    private fun extractDisplayRoot(payload: String): JSONObject {
        val root = JSONObject(payload)
        val nodes = root.optJSONArray("nodes") ?: error("リムジン時刻表の nodes がありません")
        for (nodeIndex in 0 until nodes.length()) {
            val node = nodes.optJSONObject(nodeIndex) ?: continue
            if (node.optString("type") != "data") {
                continue
            }
            val data = node.optJSONArray("data") ?: continue
            if (data.length() == 0) {
                continue
            }
            val resolved = resolveDevalue(data, data.opt(0))
            if (resolved is JSONObject && resolved.has("displayTimeTables")) {
                return resolved
            }
        }
        error("リムジン時刻表の displayTimeTables が見つかりません")
    }

    private fun resolveDevalue(pool: JSONArray, value: Any?, stack: MutableSet<Int> = mutableSetOf()): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Boolean, is String, is Double, is Long -> value
            is Number -> resolveRef(pool, value.toInt(), stack)
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until value.length()) {
                    out.put(resolveDevalue(pool, value.opt(i), stack))
                }
                out
            }
            is JSONObject -> {
                val out = JSONObject()
                for (key in value.keys()) {
                    out.put(key, resolveDevalue(pool, value.opt(key), stack))
                }
                out
            }
            else -> value
        }
    }

    /**
     * devalue の参照を辿る。プール上のプリミティブはそのまま返し、再参照しない。
     */
    private fun resolveRef(pool: JSONArray, index: Int, stack: MutableSet<Int>): Any? {
        if (index < 0 || index >= pool.length()) {
            return index
        }
        if (!stack.add(index)) {
            return null
        }
        return try {
            when (val raw = pool.opt(index)) {
                is JSONObject, is JSONArray -> resolveDevalue(pool, raw, stack)
                else -> raw
            }
        } finally {
            stack.remove(index)
        }
    }

    private fun parseDiagramTime(raw: Any?): LocalTime? {
        if (raw == null || raw == JSONObject.NULL) {
            return null
        }
        val text = raw.toString().trim()
        if (text.isEmpty() || text == "-1") {
            return null
        }
        val digits = text.filter { it.isDigit() }
        if (digits.length !in 3..4) {
            return null
        }
        val padded = digits.padStart(4, '0')
        val rawHour = padded.substring(0, 2).toIntOrNull() ?: return null
        val minute = padded.substring(2, 4).toIntOrNull() ?: return null
        if (rawHour !in 0..47 || minute !in 0..59) {
            return null
        }
        // 24・25時台は 0・1 時に正規化し、atBusTime で翌日へ回す
        return LocalTime.of(rawHour % 24, minute)
    }

    companion object {
        private const val OriginKokubunji = "国分寺駅"
        private const val OriginMusashiKoganei = "武蔵小金井駅"
        private const val TerminalT2 = "羽田空港第２ターミナル"
        private const val TerminalT1 = "羽田空港第１ターミナル"
        private const val TerminalT3 = "羽田空港第３ターミナル"
        private const val PageUrl =
            "https://www.limousinebus.co.jp/ja/timetable/detail/Haneda-KokubunjiFucyuMusahikoganeiCyofu/?dir=2"
        private const val DataUrl =
            "https://www.limousinebus.co.jp/ja/timetable/detail/Haneda-KokubunjiFucyuMusahikoganeiCyofu/__data.json?dir=2"
        private const val DesktopUserAgent =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val CompanyById = mapOf(
            "C00035Y" to BusCompany.Keio,
            "C000310" to BusCompany.TokyoAirportTransport,
        )

        private val KokubunjiHanedaVias = listOf(
            BusViaFilter.DepartHanedaT2 to TerminalT2,
            BusViaFilter.DepartHanedaT1 to TerminalT1,
            BusViaFilter.DepartHanedaT3 to TerminalT3,
        )

        private val MusashiKoganeiHanedaVias = listOf(
            BusViaFilter.MkDepartHanedaT2 to TerminalT2,
            BusViaFilter.MkDepartHanedaT1 to TerminalT1,
            BusViaFilter.MkDepartHanedaT3 to TerminalT3,
        )
    }
}
