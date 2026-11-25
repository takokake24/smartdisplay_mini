package app.smartdisplay.earthquake

import app.smartdisplay.util.NetworkLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class EarthquakeRepository {
    fun fetchRecentEew(limit: Int = 5): List<EewEvent> {
        val body = get("https://api.p2pquake.net/v2/history?codes=556&limit=$limit")
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                add(parseEew(array.getJSONObject(i)))
            }
        }
    }

    fun fetchRecentQuakes(limit: Int = 5): List<QuakeIntensityEvent> {
        val body = get("https://api.p2pquake.net/v2/history?codes=551&limit=$limit")
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                parseQuake(array.getJSONObject(i))?.let { add(it) }
            }
        }
    }

    private fun parseEew(root: JSONObject): EewEvent {
        val issue = root.optJSONObject("issue")
        val areasJson = root.optJSONArray("areas") ?: JSONArray()
        val areas = buildList {
            for (i in 0 until areasJson.length()) {
                val item = areasJson.getJSONObject(i)
                add(
                    EewArea(
                        pref = item.optString("pref"),
                        name = item.optString("name"),
                        scaleFrom = item.optDouble("scaleFrom", -1.0).toInt(),
                        scaleTo = item.optDouble("scaleTo", -1.0).toInt(),
                    ),
                )
            }
        }
        val earthquake = root.optJSONObject("earthquake")
        val origin = earthquake?.optString("originTime").orEmpty()
        return EewEvent(
            id = root.optString("id"),
            eventId = issue?.optString("eventId").orEmpty(),
            serial = issue?.optString("serial").orEmpty(),
            cancelled = root.optBoolean("cancelled", false),
            isTest = root.optBoolean("test", false),
            originTime = parseDateTime(origin),
            areas = areas,
        )
    }

    private fun parseQuake(root: JSONObject): QuakeIntensityEvent? {
        val earthquake = root.optJSONObject("earthquake") ?: return null
        val time = parseDateTime(earthquake.optString("time")) ?: return null
        val points = root.optJSONArray("points") ?: JSONArray()
        var maxKodaira = -1
        for (i in 0 until points.length()) {
            val point = points.getJSONObject(i)
            val pref = point.optString("pref")
            val addr = point.optString("addr")
            val isArea = point.optBoolean("isArea", false)
            if (!intensityPointCoversKodaira(pref, addr, isArea)) {
                continue
            }
            val scale = point.optInt("scale", -1)
            if (scale > maxKodaira) {
                maxKodaira = scale
            }
        }
        if (maxKodaira < 0) {
            return null
        }
        return QuakeIntensityEvent(
            id = root.optString("id"),
            earthquakeTime = time,
            hypocenterName = earthquake.optJSONObject("hypocenter")?.optString("name"),
            kodairaScale = maxKodaira,
        )
    }

    private fun parseDateTime(raw: String): LocalDateTime? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching {
            LocalDateTime.parse(raw.trim(), DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
        }.getOrNull()
    }

    private fun get(url: String): String = NetworkLog.trace(url) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TimeoutMs.toInt()
            readTimeout = TimeoutMs.toInt()
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("earthquake http $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val TimeoutMs = TimeUnit.SECONDS.toMillis(8)
    }
}
