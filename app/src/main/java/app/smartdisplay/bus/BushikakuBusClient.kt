package app.smartdisplay.bus

import app.smartdisplay.util.NetworkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.time.format.DateTimeFormatter

/**
 * バス比較なび（bushikaku）から中央道府中発の高速バスを取得する。
 */
class BushikakuBusClient {
    suspend fun fetchChuoDoFuchuDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        coroutineScope {
            val dayKey = date.format(DayFormatter)
            val results = DestinationPrefs.map { pref ->
                async { runCatching { fetchDestinationBuses(pref, dayKey) } }
            }.awaitAll()
            val busesById = linkedMapOf<Int, JSONObject>()
            for (result in results) {
                val buses = result.getOrNull() ?: continue
                for (bus in buses) {
                    val id = bus.optInt("id", -1)
                    if (id < 0) {
                        continue
                    }
                    val existing = busesById[id]
                    if (existing == null ||
                        bus.optJSONArray("platforms")?.length().orZero() >
                        existing.optJSONArray("platforms")?.length().orZero()
                    ) {
                        busesById[id] = bus
                    }
                }
            }
            if (busesById.isEmpty() && results.isNotEmpty() && results.all { it.isFailure }) {
                throw results.first().exceptionOrNull()
                    ?: error("中央道府中の高速バス取得に失敗しました")
            }
            busesById.values.mapNotNull { parseTrip(it, date) }
                .sortedBy { it.departureAt }
        }
    }

    private fun fetchDestinationBuses(pref: String, dayKey: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var page = 1
        var total: Int? = null
        while (page <= MaxPages) {
            val url = searchUrl(pref, dayKey, page)
            val root = downloadNextData(url)
            val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps")
                ?: error("bushikaku pageProps がありません")
            val batch = pageProps.optJSONArray("initialSearchBusesData") ?: JSONArray()
            if (total == null) {
                total = pageProps.optJSONObject("initialSearchRenderParam")?.optInt("total")
            }
            if (batch.length() == 0) {
                break
            }
            for (i in 0 until batch.length()) {
                batch.optJSONObject(i)?.let { out += it }
            }
            if (total != null && out.size >= total) {
                break
            }
            page += 1
        }
        return out
    }

    private fun parseTrip(bus: JSONObject, date: LocalDate): BusTrip? {
        val platforms = bus.optJSONArray("platforms") ?: return null
        val stops = buildList {
            for (i in 0 until platforms.length()) {
                val platform = platforms.optJSONObject(i) ?: continue
                val name = jsonText(platform, "name") ?: continue
                val timeText = jsonText(platform, "time") ?: continue
                val time = parseClock(timeText) ?: continue
                add(StopTime(name = name, time = time))
            }
        }
        val originIndex = stops.indexOfFirst { it.name == OriginStop }
        if (originIndex < 0) {
            return null
        }
        val after = stops.drop(originIndex + 1)
        if (after.isEmpty()) {
            return null
        }
        if (after.first().name in InboundNextStops) {
            return null
        }
        val departureAt = date.atBusTime(stops[originIndex].time)
        val viaArrivals = linkedMapOf<BusViaFilter, BusViaArrival>()
        for (filter in BusViaFilter.forDirection(BusDirection.ChuoDoFuchuDepart)) {
            val matched = after.firstOrNull { stop -> filter.matchesStop(stop.name) } ?: continue
            viaArrivals[filter] = BusViaArrival(
                at = date.atBusTime(matched.time),
                stopLabel = filter.displayStopLabel(listOf(matched.name)),
            )
        }
        val busStops = stops.mapIndexed { index, stop ->
            if (index == stops.lastIndex && index > 0) {
                BusStop(name = stop.name, arrival = stop.time)
            } else {
                BusStop(name = stop.name, departure = stop.time)
            }
        }
        val destinationLabel = after.last().name
        val routeName = normalizeBusRouteName(resolveRouteName(bus, destinationLabel))
        val companyName = jsonText(bus, "bus_co_name").orEmpty()
        val busId = bus.optInt("id", 0)
        return BusTrip(
            id = "bushikaku-$busId-$OriginStop-$departureAt",
            company = resolveCompany(companyName),
            routeName = routeName,
            direction = BusDirection.ChuoDoFuchuDepart,
            destinationLabel = destinationLabel,
            speechDestinationLabel = destinationLabel,
            originStop = OriginStop,
            departureAt = departureAt,
            viaArrivals = viaArrivals,
            stopRef = BusStopRef.Embedded(busStops),
        )
    }

    /**
     * 路線名。会社名・行先以外の宣伝文言は除き、
     * name → service_name → 行先 → 高速バス の順で埋める。
     */
    private fun resolveRouteName(bus: JSONObject, destinationLabel: String): String {
        jsonText(bus, "name")?.let { sanitizeRouteName(it) }?.let { return it }
        jsonText(bus, "service_name")?.let { sanitizeRouteName(it) }?.let { return it }
        return destinationLabel.ifBlank { "高速バス" }
    }

    /** 便番・号車・シート／トイレなどの余分な情報を落として路線名だけ残す。 */
    private fun sanitizeRouteName(raw: String): String? {
        var text = raw
            .replace(ServiceCodePattern, " ")
            .replace(ServiceAngleCodePattern, " ")
            .replace(ServiceCarNumberPattern, " ")
            .trim()
        text = text.replace(ServiceMarketingTailPattern, "").trim()
        while (true) {
            val stripped = text.replace(TrailingParenPattern, "").trim()
            if (stripped == text) {
                break
            }
            text = stripped
        }
        text = text.replace(ServiceAmenityTailPattern, "").trim()
        text = text.replace(Regex("""\s+"""), " ").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun resolveCompany(raw: String): BusCompany {
        return when {
            "京王" in raw -> BusCompany.Keio
            "富士急" in raw -> BusCompany.FujiQ
            "近鉄" in raw -> BusCompany.Kintetsu
            "JRバス関東" in raw || ("JR" in raw && "関東" in raw) -> BusCompany.JrBusKanto
            else -> BusCompany.Highway
        }
    }

    /** JSON null / 空 / 文字列 "null" を欠落として扱う。 */
    private fun jsonText(obj: JSONObject?, key: String): String? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) {
            return null
        }
        val value = obj.opt(key) ?: return null
        if (value === JSONObject.NULL) {
            return null
        }
        val text = value.toString().trim()
        if (text.isEmpty() || text.equals("null", ignoreCase = true)) {
            return null
        }
        return text
    }

    private fun downloadNextData(url: String): JSONObject {
        val html = NetworkLog.trace(url) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", DesktopUserAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "ja,en;q=0.8")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: error("bushikaku HTTP $code")
                }
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        return extractNextData(html)
    }

    private fun extractNextData(html: String): JSONObject {
        val marker = """<script id="__NEXT_DATA__" type="application/json">"""
        val start = html.indexOf(marker)
        if (start < 0) {
            error("bushikaku __NEXT_DATA__ が見つかりません")
        }
        val jsonStart = start + marker.length
        val jsonEnd = html.indexOf("</script>", jsonStart)
        if (jsonEnd < 0) {
            error("bushikaku __NEXT_DATA__ が閉じていません")
        }
        return JSONObject(html.substring(jsonStart, jsonEnd))
    }

    private fun parseClock(text: String): LocalTime? {
        val match = ClockPattern.matchEntire(text.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..47 || minute !in 0..59) {
            return null
        }
        return LocalTime.of(hour % 24, minute)
    }

    private fun searchUrl(pref: String, dayKey: String, page: Int): String {
        val base = "$BaseUrl/search/tokyo_${pref}/fuchu_/platform-${PlatformId}_/$dayKey/order-time/"
        return if (page <= 1) base else "${base}page-$page/"
    }

    private data class StopTime(
        val name: String,
        val time: LocalTime,
    )

    private fun Int?.orZero(): Int = this ?: 0

    companion object {
        private const val BaseUrl = "https://www.bushikaku.net"
        private const val PlatformId = "2686"
        private const val OriginStop = "中央道府中"
        private const val MaxPages = 20
        private val DayFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val ClockPattern = Regex("""(\d{1,2}):(\d{2})""")
        private val ServiceCodePattern = Regex("""【[^】]*】\s*""")
        private val ServiceAngleCodePattern = Regex("""≪[^≫]*≫\s*""")
        private val ServiceCarNumberPattern = Regex("""\d+号車\s*""")
        /** 連続空白以降（主に宣伝文）を落とす */
        private val ServiceMarketingTailPattern = Regex("""\s{2,}.*$""")
        /** 「4列シート★車内トイレ付」など設備・宣伝の末尾を落とす */
        private val ServiceAmenityTailPattern = Regex(
            """[ 　]*★?[ 　]*(?:[34]列|独立シート|車内トイレ|トイレ付き?|シート[・／]).*$""",
        )
        private val TrailingParenPattern = Regex(
            """[（(][^）)]*(?:座席|トイレ|シート|QR)[^）)]*[）)]$""",
        )
        private const val DesktopUserAgent =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 中央道府中より東へ戻る便（新宿方面）は除外する。 */
        private val InboundNextStops = setOf("中央道深大寺", "中央道三鷹", "バスタ新宿", "新宿駅西口", "新宿駅")

        private val DestinationPrefs = listOf("yamanashi", "nagano", "gifu", "aichi", "osaka", "kyoto")
    }
}
