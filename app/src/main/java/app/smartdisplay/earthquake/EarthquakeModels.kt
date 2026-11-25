package app.smartdisplay.earthquake

import java.time.LocalDateTime

data class EewEvent(
    val id: String,
    val eventId: String,
    val serial: String,
    val cancelled: Boolean,
    val isTest: Boolean,
    val originTime: LocalDateTime?,
    val areas: List<EewArea>,
)

data class EewArea(
    val pref: String,
    val name: String,
    val scaleFrom: Int,
    val scaleTo: Int,
)

data class QuakeIntensityEvent(
    val id: String,
    val earthquakeTime: LocalDateTime,
    val hypocenterName: String?,
    val kodairaScale: Int,
)

enum class EarthquakeAlertPhase {
    Idle,
    FullScreen,
    Banner,
}

/** 小平市が含まれる緊急地震速報の細分区域か。 */
fun eewAreaCoversKodaira(area: EewArea): Boolean {
    val pref = area.pref
    val name = area.name
    if (name.contains("小平")) {
        return true
    }
    val tokyo = pref.contains("東京")
    return tokyo && (name.contains("多摩東部") || name == "多摩東部")
}

/** 震度観測点が小平市を含むか。 */
fun intensityPointCoversKodaira(pref: String, addr: String, isArea: Boolean): Boolean {
    if (addr.contains("小平")) {
        return true
    }
    if (!pref.contains("東京")) {
        return false
    }
    return isArea && addr.contains("多摩東部")
}
