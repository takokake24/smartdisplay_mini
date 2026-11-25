package app.smartdisplay.bus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * NAVITIME 公開ダイヤから、国分寺市コミュニティバス（ぶんバス）の国分寺駅発を取得する。
 */
class BunbusClient {
    suspend fun fetchKokubunjiDepart(date: LocalDate): List<BusTrip> = withContext(Dispatchers.IO) {
        fetchNavitimePublicRoutes(
            routes = Routes,
            date = date,
            company = BusCompany.Community,
            direction = BusDirection.KokubunjiDepart,
            idPrefix = "bunbus",
            emptyErrorMessage = { route -> "ぶんバス時刻が空です (${route.routeName})" },
        )
    }

    companion object {
        private val Routes = listOf(
            NavitimePublicRoute(
                nodeId = "00021536", lineId = "00085222", directionIndex = 0, routeName = "本多ルート",
                originStop = "国分寺駅北口", destinationLabel = "循環（本多五丁目・本多三丁目・本町方面）",
                speechDestinationLabel = "循環、本多五丁目、本多三丁目、本町方面",
            ),
            NavitimePublicRoute(
                nodeId = "00020352", lineId = "00085223", directionIndex = 0, routeName = "東元町ルート",
                originStop = "国分寺駅南口", destinationLabel = "循環（一里塚・東元町一丁目方面）",
                speechDestinationLabel = "循環、一里塚、東元町一丁目方面",
            ),
            // 万葉・けやきは両方向が同一ダイヤに載るため dir=0 のみ取得し、行先は西国分寺経由側を代表表示
            NavitimePublicRoute(
                nodeId = "00021792", lineId = "00085226", directionIndex = 0, routeName = "万葉・けやきルート",
                originStop = "国分寺駅西", destinationLabel = "史跡武蔵国分寺跡／東恋ヶ窪三丁目",
                speechDestinationLabel = "史跡武蔵国分寺跡、または東恋ヶ窪三丁目",
                vias = listOf(BusViaOffset(BusViaFilter.DepartNishiKokubunji, "西国分寺駅東", 6)),
            ),
        )
    }
}
