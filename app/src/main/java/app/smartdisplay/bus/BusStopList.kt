package app.smartdisplay.bus

import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun transferCloudStopRef(
    operator: String,
    busstopId: String,
    trip: JSONObject,
): BusStopRef? {
    val tripId = trip.optString("id")
    val courseId = trip.optString("courseId")
    val datetime = trip.optString("departureTime")
    if (tripId.isBlank() || courseId.isBlank() || datetime.isBlank() || !trip.has("sequence")) {
        return null
    }
    return BusStopRef.TransferCloud(
        operator = operator,
        tripId = tripId,
        busstopId = busstopId,
        courseId = courseId,
        sequence = trip.getInt("sequence"),
        datetime = datetime,
    )
}

internal object BusStopList {
    private val Tokyo = ZoneId.of("Asia/Tokyo")

    fun load(ref: BusStopRef?, keio: KeioBusClient): List<BusStop> {
        val stops = when (ref) {
            null -> error("停車停留所がありません")
            is BusStopRef.Embedded -> ref.stops
            is BusStopRef.TransferCloud -> loadTransferCloud(ref)
            is BusStopRef.Navitime -> NavitimePublicDiagram.parseStops(
                NavitimePublicDiagram.download(ref.url),
            )
            is BusStopRef.Keio -> keio.fetchStopList(ref.url)
        }
        if (stops.isEmpty()) {
            error("停車停留所がありません")
        }
        return stops
    }

    private fun loadTransferCloud(ref: BusStopRef.TransferCloud): List<BusStop> {
        val datetime = URLEncoder.encode(ref.datetime, "UTF-8")
        val url = "https://transfer-cloud.navitime.biz/apiv1/${ref.operator}/trips/${ref.tripId}/sections" +
            "?busstop-id=${URLEncoder.encode(ref.busstopId, "UTF-8")}" +
            "&course-id=${URLEncoder.encode(ref.courseId, "UTF-8")}" +
            "&sequence=${ref.sequence}" +
            "&datetime=$datetime&passed=true&language=ja"
        val root = JSONObject(
            NavitimeTransferCloud.downloadJson(url, errorLabel = "Bus stops"),
        )
        val sections = root.optJSONArray("sections") ?: error("停車停留所がありません")
        val bySequence = linkedMapOf<Int, StopBuilder>()
        for (index in 0 until sections.length()) {
            val section = sections.optJSONObject(index) ?: continue
            val departure = section.optJSONObject("departure") ?: continue
            val arrival = section.optJSONObject("arrival") ?: continue
            putTime(bySequence, departure, section.optString("departureTime"), arriving = false)
            putTime(bySequence, arrival, section.optString("arrivalTime"), arriving = true)
        }
        return bySequence.entries.sortedBy { it.key }.map { it.value.toStop() }
    }

    private fun putTime(
        into: LinkedHashMap<Int, StopBuilder>,
        stop: JSONObject,
        time: String,
        arriving: Boolean,
    ) {
        val name = stop.optString("name").trim()
        if (name.isEmpty() || !stop.has("sequence")) {
            return
        }
        val clock = runCatching {
            OffsetDateTime.parse(time).atZoneSameInstant(Tokyo).toLocalTime()
        }.getOrNull() ?: return
        val current = into.getOrPut(stop.getInt("sequence")) { StopBuilder(name) }
        if (arriving) {
            current.arrival = clock
        } else {
            current.departure = clock
        }
    }

    private class StopBuilder(val name: String) {
        var arrival: LocalTime? = null
        var departure: LocalTime? = null

        fun toStop() = BusStop(name = name, arrival = arrival, departure = departure)
    }
}
