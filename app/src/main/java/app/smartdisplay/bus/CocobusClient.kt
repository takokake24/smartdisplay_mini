package app.smartdisplay.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * NAVITIME 公開ダイヤから、小金井市コミュニティバス（CoCoバス）の武蔵小金井駅発を取得する。
 */
class CocobusClient {
    suspend fun fetchMusashiKoganeiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        fetchNavitimePublicRoutes(
            routes = Routes,
            date = date,
            company = BusCompany.KoganeiCommunity,
            direction = BusDirection.MusashiKoganeiDepart,
            idPrefix = "coco",
            emptyErrorMessage = { route -> "CoCoバス時刻が空です (${route.routeName})" },
        )
    }

    companion object {
        private val Routes = listOf(
            NavitimePublicRoute(
                nodeId = "00021230", lineId = "00006277", directionIndex = 1, routeName = "北東部循環",
                originStop = "武蔵小金井駅北口", destinationLabel = "循環（地蔵通り西・東小金井駅方面）",
                speechDestinationLabel = "循環、地蔵通り西、東小金井駅方面",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartHigashiKoganei, "東小金井駅", 12)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006275", directionIndex = 1, routeName = "中町循環",
                originStop = "武蔵小金井駅南口", destinationLabel = "循環（栗山公園・新小金井駅方面）",
                speechDestinationLabel = "循環、栗山公園、新小金井駅方面",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartShinKoganei, "新小金井駅", 10)),
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00006273", directionIndex = 1, routeName = "貫井前原循環",
                originStop = "武蔵小金井駅南口", destinationLabel = "循環（神明宮入口・新町東公園方面）",
                speechDestinationLabel = "循環、神明宮入口、新町東公園方面",
            ),
            NavitimePublicRoute(
                nodeId = "00021229", lineId = "00087940", directionIndex = 1, routeName = "野川・七軒家循環",
                originStop = "武蔵小金井駅南口", destinationLabel = "循環（西念寺前・七軒家通り方面）",
                speechDestinationLabel = "循環、西念寺前、七軒家通り方面",
            ),
        )
    }
}
