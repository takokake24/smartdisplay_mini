package app.smartdisplay.train

import app.smartdisplay.bus.atBusTime
import app.smartdisplay.util.NetworkLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime

/**
 * 駅の発車時刻表（Yahoo!路線情報）。
 * 平日・土曜・休日のダイヤを日付に合わせて取り、特定日のみの列車は運転日注記で残す。
 */
class YahooTrainTimetableClient {
    suspend fun fetch(date: LocalDate, direction: TrainDirection): List<TrainTrip> =
        withContext(Dispatchers.IO) {
            val kind = trainScheduleKind(date)
            val raw = downloadList(direction, kind)
                .filter { trainListed(direction, it.destinationName) }
            val kept = filterByDriveDay(date, direction, kind, raw)
            val noted = annotateThrough(direction, kind, kept)
            noted.map { it.toTrip(date, direction) }
                .sortedWith(compareBy({ it.departureAt }, { it.id }))
        }

    suspend fun fetchStops(
        date: LocalDate,
        direction: TrainDirection,
        trip: TrainTrip,
    ): TrainRoute = withContext(Dispatchers.IO) {
        val kind = trainScheduleKind(date)
        val url = detailUrl(
            direction = direction,
            kind = kind,
            trainId = trip.trainId,
            hour = trip.timetableHour,
            minute = trip.timetableMinute,
        )
        val html = NetworkLog.trace(url) { httpGet(url) }
        val timetable = timetableOf(html)
        val stops = parseStops(timetable, direction.yahooStationId)
        return@withContext TrainRoute(
            stops = stops,
            detailNote = trainDetailServiceNote(
                direction = direction,
                trip = trip,
                stops = stops,
                guideComment = timetable.optString("guideComment"),
                displayName = timetable.optString("displayName"),
            ),
        )
    }

    private suspend fun filterByDriveDay(
        date: LocalDate,
        direction: TrainDirection,
        kind: TrainScheduleKind,
        trains: List<RawTrain>,
    ): List<RawTrain> = coroutineScope {
        val gate = Semaphore(DetailParallelism)
        trains.map { train ->
            async {
                val comment = if (!train.extra) {
                    null
                } else {
                    gate.withPermit {
                        driveCommentOrNull(direction, kind, train)
                    }
                }
                if (comment != null && !trainOperatesOn(comment, date)) {
                    null
                } else {
                    train
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * 直通は行き先で付ける。
     * 三鷹側の西船橋行きだけは、総武線と東西線の両方があるので停車駅を見る。
     */
    private suspend fun annotateThrough(
        direction: TrainDirection,
        kind: TrainScheduleKind,
        trains: List<RawTrain>,
    ): List<RawTrain> {
        val noted = trains.map { train ->
            val note = throughServiceNote(direction, train.destinationName, train.trainName)
            if (note.isNullOrEmpty()) train else train.copy(note = note)
        }
        if (!direction.isChuoLocalEast) {
            return noted
        }
        return coroutineScope {
            val gate = Semaphore(DetailParallelism)
            noted.map { train ->
                async {
                    if (trainDestinationLabel(train.destinationName) != "西船橋") {
                        train
                    } else {
                        val note = gate.withPermit { tozaiNoteOrNull(direction, kind, train) }
                        if (note.isNullOrEmpty()) train.copy(note = "") else train.copy(note = note)
                    }
                }
            }.awaitAll()
        }
    }

    private fun tozaiNoteOrNull(
        direction: TrainDirection,
        kind: TrainScheduleKind,
        train: RawTrain,
    ): String? {
        return try {
            val url = detailUrl(
                direction = direction,
                kind = kind,
                trainId = train.trainId,
                hour = train.hour,
                minute = train.minute,
            )
            val html = NetworkLog.trace(url) { httpGet(url) }
            val timetable = timetableOf(html)
            val stops = timetable.optJSONArray("stopStation") ?: return null
            var viaTozai = false
            var viaToyo = false
            for (index in 0 until stops.length()) {
                val name = stops.optJSONObject(index)?.optString("stationName").orEmpty()
                val label = trainDestinationLabel(name)
                if (label in ToyoThroughDestinations) {
                    viaToyo = true
                }
                if (isTozaiStation(name)) {
                    viaTozai = true
                }
            }
            when {
                viaToyo -> ToyoThroughNote
                viaTozai -> TozaiThroughNote
                else -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun isTozaiStation(name: String): Boolean {
        return name.startsWith("落合") ||
            name.startsWith("高田馬場") ||
            name.startsWith("東陽町") ||
            name.startsWith("妙典") ||
            name.startsWith("東葉") ||
            name.startsWith("浦安") ||
            name.startsWith("門前仲町") ||
            name.startsWith("木場") ||
            name.startsWith("南砂町") ||
            name.startsWith("西葛西") ||
            name.startsWith("葛西") ||
            name.startsWith("南行徳") ||
            name.startsWith("行徳") ||
            name.startsWith("原木中山")
    }

    private fun driveCommentOrNull(
        direction: TrainDirection,
        kind: TrainScheduleKind,
        train: RawTrain,
    ): String? {
        return try {
            val url = detailUrl(
                direction = direction,
                kind = kind,
                trainId = train.trainId,
                hour = train.hour,
                minute = train.minute,
            )
            val html = NetworkLog.trace(url) { httpGet(url) }
            timetableOf(html).optString("driveComment")
        } catch (error: CancellationException) {
            throw error
        } catch (error: InterruptedException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun downloadList(direction: TrainDirection, kind: TrainScheduleKind): List<RawTrain> {
        val url = listUrl(direction, kind)
        return NetworkLog.trace(url) {
            parseList(httpGet(url))
        }
    }

    private fun listUrl(direction: TrainDirection, kind: TrainScheduleKind): String {
        return "https://transit.yahoo.co.jp/timetable/${direction.yahooStationId}/${direction.yahooGroupId}?kind=${kind.yahooKind}"
    }

    private fun detailUrl(
        direction: TrainDirection,
        kind: TrainScheduleKind,
        trainId: String,
        hour: Int,
        minute: Int,
    ): String {
        return "https://transit.yahoo.co.jp/timetable/${direction.yahooStationId}/${direction.yahooGroupId}/" +
            "$trainId?kind=${kind.yahooKind}&hh=$hour&mm=$minute"
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", DesktopUserAgent)
            setRequestProperty("Accept-Language", "ja,en;q=0.8")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: error("Yahoo timetable HTTP $code")
            }
            val html = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if ("__NEXT_DATA__" !in html) {
                error("Yahoo timetable has no data")
            }
            return html
        } finally {
            connection.disconnect()
        }
    }

    private fun parseList(html: String): List<RawTrain> {
        val item = extractNextData(html)
            .getJSONObject("props")
            .getJSONObject("pageProps")
            .getJSONObject("timetableItem")
        val kinds = idNameMap(item.optJSONObject("master")?.optJSONArray("kind"))
        val destinations = idNameMap(item.optJSONObject("master")?.optJSONArray("destination"))
        val directionOrigin = item.optString("firstStation") == "true"
        val hours = item.optJSONArray("hourTimeTable") ?: error("時刻表がありません")
        val trains = mutableListOf<RawTrain>()
        var skipped = 0
        for (hourIndex in 0 until hours.length()) {
            val hourObject = hours.optJSONObject(hourIndex) ?: continue
            val hour = hourObject.optString("hour").toIntOrNull()
            val minutes = hourObject.optJSONArray("minTimeTable")
            if (hour == null || minutes == null || hour !in 0..30) {
                skipped += 1
                continue
            }
            for (minuteIndex in 0 until minutes.length()) {
                val entry = minutes.optJSONObject(minuteIndex)
                val minute = entry?.optString("minute")?.toIntOrNull()
                val trainId = entry?.optString("trainId").orEmpty()
                if (entry == null || minute == null || minute !in 0..59 || trainId.isBlank()) {
                    skipped += 1
                    continue
                }
                trains += RawTrain(
                    trainId = trainId,
                    hour = hour,
                    minute = minute,
                    kindName = kinds[entry.optString("kindId")].orEmpty(),
                    trainName = entry.optString("trainName"),
                    destinationName = destinations[entry.optString("destinationId")].orEmpty(),
                    extra = entry.optString("extraTrain") == "true",
                    originatesHere = directionOrigin || entry.optString("firstStation") == "true",
                )
            }
        }
        if ((trains.isEmpty() && skipped > 0) ||
            (trains.isNotEmpty() && (kinds.isEmpty() || destinations.isEmpty()))
        ) {
            error("時刻表を読み取れませんでした")
        }
        return trains
    }

    private fun idNameMap(array: JSONArray?): Map<String, String> {
        if (array == null) {
            return emptyMap()
        }
        val map = linkedMapOf<String, String>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optString("id")
            val name = entry.optString("name")
            if (id.isNotBlank() && name.isNotBlank()) {
                map[id] = name
            }
        }
        return map
    }

    private fun extractNextData(html: String): JSONObject {
        val markerAt = html.indexOf("id=\"__NEXT_DATA__\"")
        if (markerAt < 0) {
            error("時刻表データを取得できませんでした")
        }
        val start = html.indexOf('>', markerAt)
        val end = if (start >= 0) html.indexOf("</script>", start) else -1
        if (start < 0 || end < 0) {
            error("時刻表データを取得できませんでした")
        }
        return JSONObject(html.substring(start + 1, end))
    }

    private fun timetableOf(html: String): JSONObject {
        return extractNextData(html)
            .getJSONObject("props")
            .getJSONObject("pageProps")
            .getJSONObject("timetableStationTrainResult")
            .getJSONObject("timetable")
    }

    private fun parseStops(timetable: JSONObject, stationId: String): List<TrainStop> {
        val array = timetable.optJSONArray("stopStation") ?: error("停車駅がありません")
        val stops = mutableListOf<TrainStop>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val name = trainDestinationLabel(entry.optString("stationName"))
            if (name.isBlank()) {
                continue
            }
            stops += TrainStop(
                name = name,
                arrival = parseYahooClock(clockOrNull(entry, "arrivalTime")),
                departure = parseYahooClock(clockOrNull(entry, "departureTime")),
                isOrigin = entry.optString("stationCode") == stationId,
            )
        }
        if (stops.isEmpty()) {
            error("停車駅がありません")
        }
        return stops
    }

    private fun clockOrNull(entry: JSONObject, key: String): String? {
        if (!entry.has(key) || entry.isNull(key)) {
            return null
        }
        val raw = entry.optString(key).trim()
        if (raw.isEmpty() || raw == "null") {
            return null
        }
        return raw
    }

    private fun parseYahooClock(raw: String?): LocalTime? {
        if (raw == null) {
            return null
        }
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in 3..4) {
            return null
        }
        val value = digits.toIntOrNull() ?: return null
        val hour = value / 100
        val minute = value % 100
        if (hour !in 0..30 || minute !in 0..59) {
            return null
        }
        return LocalTime.of(hour % 24, minute)
    }

    private fun RawTrain.toTrip(date: LocalDate, direction: TrainDirection): TrainTrip {
        val typeLabel = trainTypeLabel(kindName, trainName, direction)
        val clockHour = if (hour >= 24) hour - 24 else hour
        return TrainTrip(
            id = "${direction.name}-$trainId-$hour-$minute",
            departureAt = date.atBusTime(LocalTime.of(clockHour, minute)),
            typeLabel = typeLabel,
            trainName = trainDisplayName(kindName, trainName),
            destination = trainDestinationLabel(destinationName),
            accent = trainTypeColor(typeLabel),
            trainId = trainId,
            timetableHour = hour,
            timetableMinute = minute,
            note = note,
            originatesHere = originatesHere,
        )
    }

    private data class RawTrain(
        val trainId: String,
        val hour: Int,
        val minute: Int,
        val kindName: String,
        val trainName: String,
        val destinationName: String,
        val extra: Boolean,
        val note: String = "",
        val originatesHere: Boolean = false,
    )

    private companion object {
        const val DetailParallelism = 4
        const val DesktopUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
