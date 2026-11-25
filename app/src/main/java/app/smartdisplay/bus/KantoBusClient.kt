package app.smartdisplay.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * NAVITIME 公開ダイヤから、武蔵小金井駅発の関東バスを取得する。
 */
class KantoBusClient {
    suspend fun fetchMusashiKoganeiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        fetchNavitimePublicRoutes(
            routes = Routes,
            date = date,
            company = BusCompany.Kanto,
            direction = BusDirection.MusashiKoganeiDepart,
            idPrefix = "kanto",
            emptyErrorMessage = { route -> "関東バス時刻が空です (${route.routeName})" },
            normalizeRouteName = true,
            distinctKey = { "${it.routeName}|${it.destinationLabel}|${it.departureAt}" },
        )
    }

    companion object {
        private val Routes = listOf(
            NavitimePublicRoute(
                nodeId = "00023934", lineId = "00016447", directionIndex = 0, routeName = "鷹33/34",
                originStop = "武蔵小金井駅", destinationLabel = "三鷹駅",
                vias = listOf(BusViaOffset(BusViaFilter.MkDepartKoganeiBridge, "小金井橋", 3), BusViaOffset(BusViaFilter.MkDepartMitaka, "三鷹駅", 27)),
            ),
        )
    }
}
