package app.smartdisplay.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 西武バス（transfer-cloud）から上水本町五丁目発・一橋学園／小平方面を取得する。
 */
class SeibuBusClient {
    suspend fun fetchHitotsubashiDirection(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        parseTrips(
            date = date,
            busstopId = JosuiHonchoId,
            originStop = JosuiHonchoName,
            courseSequence = KodairaBoundSequence,
            direction = BusDirection.HitotsubashiKodaira,
        )
    }

    suspend fun fetchKokubunjiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        val north = parseTrips(
            date = date,
            busstopId = KokubunjiNorthId,
            originStop = KokubunjiNorthName,
            courseSequence = KokubunjiNorthCourseSequence,
            direction = BusDirection.KokubunjiDepart,
        )
        val northEntrance = parseTrips(
            date = date,
            busstopId = KokubunjiNorthEntranceId,
            originStop = KokubunjiNorthEntranceName,
            courseSequence = KokubunjiNorthEntranceCourseSequence,
            direction = BusDirection.KokubunjiDepart,
        )
        (north + northEntrance)
            .distinctBy { "${it.originStop}|${it.routeName}|${it.destinationLabel}|${it.departureAt}" }
            .sortedBy { it.departureAt }
    }

    suspend fun fetchMusashiKoganeiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        MusashiKoganeiCourseSequences.flatMap { sequence ->
            parseTrips(
                date = date,
                busstopId = MusashiKoganeiId,
                originStop = MusashiKoganeiName,
                courseSequence = sequence,
                direction = BusDirection.MusashiKoganeiDepart,
            )
        }
            .distinctBy { "${it.routeName}|${it.destinationLabel}|${it.departureAt}" }
            .sortedBy { it.departureAt }
    }

    private fun parseTrips(
        date: LocalDate,
        busstopId: String,
        originStop: String,
        courseSequence: String,
        direction: BusDirection,
    ): List<BusTrip> {
        val json = JSONObject(
            NavitimeTransferCloud.downloadJson(
                "$TimetableBase?busstop-id=$busstopId&course-sequence=$courseSequence&language=ja",
                errorLabel = "Seibu bus",
            ),
        )
        val tripsArray = NavitimeTransferCloud.tripsArrayForDate(json, date) ?: return emptyList()
        val tokyo = ZoneId.of("Asia/Tokyo")
        val out = ArrayList<BusTrip>(tripsArray.length())
        for (i in 0 until tripsArray.length()) {
            val trip = tripsArray.getJSONObject(i)
            val destination = trip.optString("destination")
            val departureOd = OffsetDateTime.parse(trip.getString("departureTime"))
            val clock = departureOd.atZoneSameInstant(tokyo).toLocalTime()
            val departureAt = date.atBusTime(clock)
            val routeName = normalizeBusRouteName(trip.optString("courseName"))
            val night = isNightBusLabel(
                trip.optString("courseName"),
                NavitimeTransferCloud.rubyCaptions(trip.optJSONArray("rubies")),
            )
            val viaArrivals = when (direction) {
                BusDirection.HitotsubashiKodaira ->
                    buildViaArrivals(destination, routeName, departureAt)
                BusDirection.KokubunjiDepart ->
                    buildDepartViaArrivals(originStop, destination, routeName, departureAt)
                BusDirection.MusashiKoganeiDepart ->
                    buildMusashiKoganeiViaArrivals(destination, routeName, departureAt)
                else -> emptyMap()
            }
            val (destLabel, speechLabel) = when (direction) {
                BusDirection.MusashiKoganeiDepart -> musashiKoganeiDestinationLabels(destination, routeName)
                else -> destinationLabels(destination, routeName)
            }
            out += BusTrip(
                id = "seibu-${trip.optString("courseId")}-$originStop-$departureAt",
                company = BusCompany.Seibu,
                routeName = routeName,
                direction = direction,
                destinationLabel = destLabel,
                speechDestinationLabel = speechLabel,
                originStop = originStop,
                departureAt = departureAt,
                isNightBus = night,
                viaArrivals = viaArrivals,
                stopRef = transferCloudStopRef(
                    operator = "seibubus",
                    busstopId = busstopId,
                    trip = trip,
                ),
            )
        }
        return out.sortedBy { it.departureAt }
    }

    private fun buildViaArrivals(
        destination: String,
        routeName: String,
        departureAt: java.time.LocalDateTime,
    ): Map<BusViaFilter, BusViaArrival> {
        val out = linkedMapOf<BusViaFilter, BusViaArrival>()
        fun put(filter: BusViaFilter, stop: String, minutes: Long) {
            out[filter] = BusViaArrival(at = departureAt.plusMinutes(minutes), stopLabel = stop)
        }
        when {
            destination.contains("一橋学園駅") -> put(BusViaFilter.HitotsubashiStation, "一橋学園駅", 8)
            destination.contains("小平駅南口") || destination.contains("小平駅") -> {
                put(BusViaFilter.KodairaStation, "小平駅南口", 15)
                put(BusViaFilter.HitotsubashiStation, "一橋学園駅", 8)
                put(BusViaFilter.CityHall, "小平市役所", 11)
                put(BusViaFilter.OmeKaidoStation, "青梅街道駅", 13)
            }
            destination.contains("循環") || routeName.contains("寺64") -> {
                put(BusViaFilter.ShinKodairaStation, "新小平駅", 12)
                put(BusViaFilter.OmeKaidoStation, "青梅街道駅", 10)
                put(BusViaFilter.CityHall, "小平市役所", 14)
            }
        }
        // 寺62系は松ヶ丘経由で小平駅・青梅街道
        if (routeName.contains("寺62") && !routeName.contains("寺62-1")) {
            put(BusViaFilter.KodairaStation, "小平駅南口", 16)
            put(BusViaFilter.OmeKaidoStation, "青梅街道駅", 12)
        }
        if (routeName.contains("寺62-1") || routeName.contains("寺62－1")) {
            put(BusViaFilter.HitotsubashiStation, "一橋学園駅", 8)
        }
        if (routeName.contains("寺61")) {
            put(BusViaFilter.KodairaStation, "小平駅南口", 15)
            put(BusViaFilter.HitotsubashiStation, "一橋学園駅", 8)
            put(BusViaFilter.CityHall, "小平市役所", 11)
        }
        return out
    }

    private fun buildDepartViaArrivals(
        originStop: String,
        destination: String,
        routeName: String,
        departureAt: java.time.LocalDateTime,
    ): Map<BusViaFilter, BusViaArrival> {
        val out = linkedMapOf<BusViaFilter, BusViaArrival>()
        fun put(filter: BusViaFilter, stop: String, minutes: Long) {
            out[filter] = BusViaArrival(at = departureAt.plusMinutes(minutes), stopLabel = stop)
        }
        if (originStop.contains("北口") && !originStop.contains("北入口")) {
            if (destination.contains("美術大学") || destination.contains("美大") || destination.contains("小平営業所")) {
                put(BusViaFilter.DepartTakanodai, "鷹の台駅入口", 14)
            }
            return out
        }
        // 国分寺駅北入口発（上水本町発より手前なのでオフセットを加算）
        when {
            destination.contains("一橋学園駅") || routeName.contains("寺62-1") || routeName.contains("寺62－1") -> {
                put(BusViaFilter.DepartHitotsubashi, "一橋学園駅", 14)
            }
            destination.contains("小平駅南口") || destination.contains("小平駅") || routeName.contains("寺61") -> {
                put(BusViaFilter.DepartKodaira, "小平駅南口", 21)
                put(BusViaFilter.DepartHitotsubashi, "一橋学園駅", 14)
                put(BusViaFilter.DepartCityHall, "小平市役所", 17)
                put(BusViaFilter.DepartOmeKaido, "青梅街道駅", 19)
            }
            destination.contains("循環") || routeName.contains("寺64") -> {
                put(BusViaFilter.DepartShinKodaira, "新小平駅", 18)
                put(BusViaFilter.DepartOmeKaido, "青梅街道駅", 16)
                put(BusViaFilter.DepartCityHall, "小平市役所", 20)
            }
        }
        if (routeName.contains("寺62") && !routeName.contains("寺62-1") && !routeName.contains("寺62－1")) {
            put(BusViaFilter.DepartKodaira, "小平駅南口", 22)
            put(BusViaFilter.DepartOmeKaido, "青梅街道駅", 18)
        }
        return out
    }

    private fun buildMusashiKoganeiViaArrivals(
        destination: String,
        routeName: String,
        departureAt: java.time.LocalDateTime,
    ): Map<BusViaFilter, BusViaArrival> {
        val out = linkedMapOf<BusViaFilter, BusViaArrival>()
        fun put(filter: BusViaFilter, stop: String, minutes: Long) {
            out[filter] = BusViaArrival(at = departureAt.plusMinutes(minutes), stopLabel = stop)
        }
        put(BusViaFilter.MkDepartKoganeiBridge, "小金井橋", 4)
        when {
            destination.contains("清瀬") || routeName.contains("武13") -> {
                put(BusViaFilter.MkDepartHanakoganei, "花小金井駅入口", 11)
                put(BusViaFilter.MkDepartKiyose, "清瀬駅南口", 40)
            }
            destination.contains("東久留米") || routeName.contains("武12") || routeName.contains("武21") -> {
                put(BusViaFilter.MkDepartHanakoganei, "花小金井駅入口", 10)
                put(BusViaFilter.MkDepartHigashiKurume, "東久留米駅西口", 33)
            }
            destination.contains("久留米西団地") || destination.contains("滝山") ||
                routeName.contains("武15") || routeName.contains("武14") -> {
                put(BusViaFilter.MkDepartHanakoganei, "花小金井駅入口", 10)
            }
            destination.contains("花小金井駅南口") || routeName.contains("武16") || routeName.contains("花12") -> {
                put(BusViaFilter.MkDepartKaita, "回田町", 10)
                put(BusViaFilter.MkDepartHanakoganei, "花小金井駅南口", 15)
            }
            destination.contains("小平駅") || routeName.contains("武20") -> {
                put(BusViaFilter.MkDepartKaita, "回田町", 10)
                put(BusViaFilter.MkDepartKodaira, "小平駅南口", 23)
            }
            destination.contains("小平営業所") || routeName.contains("武19") -> {
                put(BusViaFilter.MkDepartKaita, "回田町", 11)
                put(BusViaFilter.MkDepartHitotsubashi, "一橋学園駅", 18)
            }
            destination.contains("小平団地") -> {
                put(BusViaFilter.MkDepartKaita, "回田町", 10)
            }
            destination.contains("国際電気") || routeName.contains("武17") || routeName.contains("武20-1") -> {
                put(BusViaFilter.MkDepartKaita, "回田町", 10)
            }
            destination.contains("花小金井駅") || destination.contains("花小駅") -> {
                put(BusViaFilter.MkDepartHanakoganei, "花小金井駅入口", 12)
            }
        }
        if ("深夜" in destination || "深夜" in routeName) {
            put(BusViaFilter.MkDepartHanakoganei, "花小金井駅", 20)
        }
        return out
    }

    private fun musashiKoganeiDestinationLabels(
        destination: String,
        routeName: String,
    ): Pair<String, String> {
        if ("深夜" in destination || "深夜" in routeName) {
            // 「行き」を含むので destinationDisplayText で二重付与されない
            return "花小金井駅行き" to "花小金井駅"
        }
        val short = shortMusashiKoganeiDestination(destination)
        return short to short
    }

    private fun shortMusashiKoganeiDestination(destination: String): String {
        val cleaned = destination
            .replace("武蔵小金井駅（前沢宿）", "")
            .replace("武蔵小金井駅（御成橋）", "")
            .replace("武蔵小金井駅（熊の宮）", "")
            .replace("武蔵小金井駅（国際）", "")
            .replace("武蔵小金井駅（昭和病院）", "")
            .replace("武蔵小金井駅（花小駅入口）", "")
            .replace("武蔵小金井駅（錦城高）", "")
            .replace("武蔵小金井駅（錦城）", "")
            .replace("武蔵小金井駅→", "")
            .replace("武蔵小金井駅～", "")
            .replace("武蔵小金井駅", "")
            .replace("→", " ")
            .trim()
        return cleaned.ifBlank { destination }
    }

    private fun destinationLabels(destination: String, routeName: String): Pair<String, String> {
        if (destination.contains("循環") || routeName.contains("寺64")) {
            return "循環（新小平駅方面）" to "循環、新小平駅方面"
        }
        val short = shortDestinationLabel(destination)
        return short to short
    }

    private fun shortDestinationLabel(destination: String): String {
        val cleaned = destination
            .replace("国分寺駅北入口（東町）", "")
            .replace("国分寺駅北入口（松ヶ丘）", "")
            .replace("国分寺駅北入口～", "")
            .replace("国分寺駅北入口（循環）", "循環")
            .replace("国分寺駅北入口", "")
            .replace("国分寺駅北口（美大）", "")
            .replace("国分寺駅北口～", "")
            .replace("国分寺駅北口", "")
            .trim()
            .removePrefix("（")
            .removeSuffix("）")
            .trim()
        return cleaned.ifBlank { destination }
    }

    companion object {
        private const val JosuiHonchoId = "00110713"
        private const val JosuiHonchoName = "上水本町五丁目"
        private const val KokubunjiNorthId = "00113434"
        private const val KokubunjiNorthName = "国分寺駅北口"
        private const val KokubunjiNorthEntranceId = "00110687"
        private const val KokubunjiNorthEntranceName = "国分寺駅北入口"
        private const val KodairaBoundSequence = "0001900606-5"
        private const val KokubunjiNorthCourseSequence = "0001901175-1"
        private const val KokubunjiNorthEntranceCourseSequence = "0001900606-1"
        private const val MusashiKoganeiId = "00110478"
        private const val MusashiKoganeiName = "武蔵小金井駅"
        private val MusashiKoganeiCourseSequences = listOf(
            "0001900864-1", "0001900867-1", "0001900873-1", "0001900887-1", "0001900889-1", "0001901486-1",
            "0001901472-1", "0001901467-1", "0001901295-1", "0001901470-1", "0001901471-1", "0001900538-1",
            "0001900798-1", "0001900800-1",
        )
        private const val TimetableBase =
            "https://transfer-cloud.navitime.biz/apiv1/seibubus/busstops/timetables"
    }
}
