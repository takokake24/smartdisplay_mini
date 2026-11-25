package app.smartdisplay.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 立川バス公式（Navitime transfer-cloud）API から上水南町発を取得する。
 */
class TachikawaBusClient {
    suspend fun fetchKokubunjiDirection(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        parseTrips(
            date = date,
            busstopId = JosuiMinamichoId,
            originStop = JosuiMinamichoName,
            courseSequence = KokubunjiCourseSequence,
            direction = BusDirection.KokubunjiMusashiKoganei,
            showArrivalByDefault = false,
        )
    }

    suspend fun fetchHanakoganeiDirection(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        parseTrips(
            date = date,
            busstopId = JosuiMinamichoId,
            originStop = JosuiMinamichoName,
            courseSequence = HanakoganeiCourseSequence,
            direction = BusDirection.HanakoganeiShowa,
            showArrivalByDefault = false,
        )
    }

    suspend fun fetchKokubunjiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        parseTrips(
            date = date,
            busstopId = KokubunjiNorthId,
            originStop = KokubunjiNorthName,
            courseSequence = KokubunjiNorthCourseSequence,
            direction = BusDirection.KokubunjiDepart,
            showArrivalByDefault = false,
        )
    }

    private fun parseTrips(
        date: LocalDate,
        busstopId: String,
        originStop: String,
        courseSequence: String,
        direction: BusDirection,
        showArrivalByDefault: Boolean,
    ): List<BusTrip> {
        val json = JSONObject(
            NavitimeTransferCloud.downloadJson(
                "$TimetableBase?busstop-id=$busstopId&course-sequence=$courseSequence&language=ja",
                errorLabel = "Tachikawa bus",
            ),
        )
        val tripsArray = NavitimeTransferCloud.tripsArrayForDate(json, date) ?: return emptyList()
        val tokyo = ZoneId.of("Asia/Tokyo")
        val out = ArrayList<BusTrip>(tripsArray.length())
        for (i in 0 until tripsArray.length()) {
            val trip = tripsArray.getJSONObject(i)
            val destination = trip.optString("destination")
            if (direction == BusDirection.KokubunjiMusashiKoganei && destination != "国分寺駅北口") {
                continue
            }
            val departureOd = OffsetDateTime.parse(trip.getString("departureTime"))
            val clock = departureOd.atZoneSameInstant(tokyo).toLocalTime()
            val departureAt = date.atBusTime(clock)
            val routeName = normalizeBusRouteName(trip.optString("courseName"))
            val night = isNightBusLabel(trip.optString("courseName"), NavitimeTransferCloud.rubyCaptions(trip.optJSONArray("rubies")))
            val viaArrivals = buildViaArrivals(direction, destination, departureAt)
            val defaultArrivalMinutes = when (direction) {
                BusDirection.KokubunjiMusashiKoganei -> TravelMinutesToKokubunjiNorth
                else -> viaArrivals.values.minOfOrNull { DurationMinutes(departureAt, it.at) }
                    ?: 15L
            }
            val arrivalAt = departureAt.plusMinutes(defaultArrivalMinutes)
            val arrivalLabel = when (direction) {
                BusDirection.KokubunjiMusashiKoganei -> "国分寺駅北口"
                else -> null
            }
            val (destLabel, speechLabel) = when (direction) {
                BusDirection.KokubunjiMusashiKoganei -> "国分寺駅北口" to "国分寺駅北口"
                else -> destinationLabels(destination)
            }
            out += BusTrip(
                id = "tachikawa-${trip.optString("courseId")}-$departureAt",
                company = BusCompany.Tachikawa,
                routeName = routeName,
                direction = direction,
                destinationLabel = destLabel,
                speechDestinationLabel = speechLabel,
                originStop = originStop,
                departureAt = departureAt,
                arrivalAt = if (showArrivalByDefault) arrivalAt else null,
                arrivalStopLabel = if (showArrivalByDefault) arrivalLabel else null,
                isNightBus = night,
                viaArrivals = viaArrivals,
                stopRef = transferCloudStopRef(
                    operator = "tachikawabus",
                    busstopId = busstopId,
                    trip = trip,
                ),
            )
        }
        return out.sortedBy { it.departureAt }
    }

    private fun buildViaArrivals(
        direction: BusDirection,
        destination: String,
        departureAt: java.time.LocalDateTime,
    ): Map<BusViaFilter, BusViaArrival> {
        when (direction) {
            BusDirection.KokubunjiMusashiKoganei -> {
                return if (destination.contains("国分寺駅北口")) {
                    mapOf(
                        BusViaFilter.KokubunjiNorth to BusViaArrival(
                            at = departureAt.plusMinutes(TravelMinutesToKokubunjiNorth),
                            stopLabel = "国分寺駅北口",
                        ),
                    )
                } else {
                    emptyMap()
                }
            }
            BusDirection.KokubunjiDepart -> return buildDepartViaArrivals(destination, departureAt)
            BusDirection.HanakoganeiShowa -> Unit
            BusDirection.HitotsubashiKodaira,
            BusDirection.MusashiKoganeiDepart,
            BusDirection.ChuoDoFuchuDepart,
            -> return emptyMap()
        }
        val out = linkedMapOf<BusViaFilter, BusViaArrival>()
        fun add(filter: BusViaFilter, stop: String, minutes: Long) {
            if (out.containsKey(filter) && filter.preferName != null && stop != filter.preferName) {
                return
            }
            out[filter] = BusViaArrival(at = departureAt.plusMinutes(minutes), stopLabel = stop)
        }
        when {
            destination.contains("花小金井駅南口") -> add(BusViaFilter.HanakoganeiStation, "花小金井駅南口", 15)
            destination.contains("花小金井駅北口") -> add(BusViaFilter.HanakoganeiStation, "花小金井駅北口", 15)
            destination == "昭和病院" -> add(BusViaFilter.ShowaHospital, "昭和病院", 18)
            destination.contains("昭和病院前") -> add(BusViaFilter.ShowaHospital, "昭和病院前", 17)
            destination.contains("大沼団地") -> add(BusViaFilter.ShowaHospital, "昭和病院前", 17)
        }
        // 両方通る想定の便は preferName=昭和病院 を優先（昭和病院行）
        if (destination == "昭和病院") {
            out[BusViaFilter.ShowaHospital] = BusViaArrival(
                at = departureAt.plusMinutes(18),
                stopLabel = "昭和病院",
            )
        }
        return out
    }

    private fun buildDepartViaArrivals(
        destination: String,
        departureAt: java.time.LocalDateTime,
    ): Map<BusViaFilter, BusViaArrival> {
        val out = linkedMapOf<BusViaFilter, BusViaArrival>()
        fun add(filter: BusViaFilter, stop: String, minutes: Long) {
            out[filter] = BusViaArrival(at = departureAt.plusMinutes(minutes), stopLabel = stop)
        }
        // 国分寺駅北口→上水南町は約8分。そこから先は上水南町発の既存オフセットを足す。
        add(BusViaFilter.DepartMalvern, "上水南町", 8)
        when {
            destination.contains("花小金井駅南口") -> add(BusViaFilter.DepartHanakoganei, "花小金井駅南口", 23)
            destination.contains("花小金井駅北口") -> add(BusViaFilter.DepartHanakoganei, "花小金井駅北口", 23)
            destination == "昭和病院" -> add(BusViaFilter.DepartShowaHospital, "昭和病院", 26)
            destination.contains("昭和病院前") -> add(BusViaFilter.DepartShowaHospital, "昭和病院前", 25)
            destination.contains("大沼団地") -> add(BusViaFilter.DepartShowaHospital, "昭和病院前", 25)
            destination.contains("回田循環") -> {
                add(BusViaFilter.DepartHanakoganei, "花小金井駅南口", 23)
                add(BusViaFilter.DepartShowaHospital, "昭和病院前", 25)
            }
        }
        return out
    }

    private fun destinationLabels(destination: String): Pair<String, String> {
        return when {
            destination.contains("回田循環（朝回り）") ->
                "回田循環（朝回り：回田本通り入口・小平団地中央方面）" to
                    "回田循環朝回り、回田本通り入口、小平団地中央方面"
            destination.contains("回田循環（夕回り）") ->
                "回田循環（夕回り：小平団地中央・回田本通り入口方面）" to
                    "回田循環夕回り、小平団地中央、回田本通り入口方面"
            else -> {
                val short = shortDestinationLabel(destination)
                short to short
            }
        }
    }

    private fun shortDestinationLabel(destination: String): String {
        return destination
            .removePrefix("国分寺駅北入口（東町）")
            .removePrefix("国分寺駅北入口（松ヶ丘）")
            .removePrefix("国分寺駅北入口～")
            .removePrefix("国分寺駅北入口（循環）")
            .ifBlank { destination }
    }

    private fun DurationMinutes(from: java.time.LocalDateTime, to: java.time.LocalDateTime): Long =
        java.time.Duration.between(from, to).toMinutes().coerceAtLeast(1)

    companion object {
        private const val JosuiMinamichoId = "00430428"
        private const val JosuiMinamichoName = "上水南町"
        private const val KokubunjiNorthId = "00430003"
        private const val KokubunjiNorthName = "国分寺駅北口"
        private const val TravelMinutesToKokubunjiNorth = 8L
        private const val TimetableBase =
            "https://transfer-cloud.navitime.biz/apiv1/tachikawabus/busstops/timetables"
        private const val KokubunjiCourseSequence = "0007400173-19"
        private const val HanakoganeiCourseSequence = "0007400320-6"
        private const val KokubunjiNorthCourseSequence = "0007400320-1"
    }
}
