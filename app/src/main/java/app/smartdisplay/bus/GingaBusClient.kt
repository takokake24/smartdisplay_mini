package app.smartdisplay.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * NAVITIME 公開ダイヤから、つつじ公園発・銀河鉄道（小平国分寺線）小平駅南口方面を取得する。
 */
class GingaBusClient {
    suspend fun fetchHitotsubashiDirection(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        val html = NavitimePublicDiagram.download(DiagramUrl)
        val kind = busScheduleKind(date)
        val departures = NavitimePublicDiagram.parseDepartureEntries(html, kind)
            .distinctBy { it.time }
            .sortedBy { it.time }
        if (departures.isEmpty()) {
            error("銀河鉄道バス時刻が空です")
        }
        departures.map { entry ->
            val departureAt = date.atBusTime(entry.time)
            val arrivalAt = departureAt.plusMinutes(TravelMinutesToKodairaSouth)
            BusTrip(
                id = "ginga-$RouteId-$departureAt",
                company = BusCompany.Ginga,
                routeName = "小平国分寺線",
                direction = BusDirection.HitotsubashiKodaira,
                destinationLabel = "小平駅南口",
                speechDestinationLabel = "小平駅南口",
                originStop = TsutsujiParkName,
                departureAt = departureAt,
                viaArrivals = mapOf(
                    BusViaFilter.KodairaStation to BusViaArrival(
                        at = arrivalAt,
                        stopLabel = "小平駅南口",
                    ),
                ),
                stopRef = entry.stopsUrl.takeIf { it.isNotEmpty() }?.let { BusStopRef.Navitime(it) },
            )
        }
    }

    companion object {
        private const val TsutsujiParkName = "つつじ公園"
        private const val RouteId = "00054381"
        private const val TravelMinutesToKodairaSouth = 12L
        // node=つつじ公園 / line=小平国分寺線 / 0=小平駅南口方面
        private const val DiagramUrl =
            "https://www.navitime.co.jp/diagram/bus/00296103/00054381/0/"
    }
}
