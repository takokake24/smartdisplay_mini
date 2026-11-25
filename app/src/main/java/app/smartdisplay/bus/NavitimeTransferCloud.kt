package app.smartdisplay.bus

import app.smartdisplay.util.NetworkLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDate

/**
 * Navitime transfer-cloud（西武・立川など）の JSON 時刻表向け共通処理。
 */
internal object NavitimeTransferCloud {
    private const val MobileUserAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"

    fun downloadJson(url: String, errorLabel: String): String = NetworkLog.trace(url) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", MobileUserAgent)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "ja,en;q=0.8")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: error("$errorLabel HTTP $code")
            }
            BufferedReader(InputStreamReader(stream, Charset.forName("UTF-8"))).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun rubyCaptions(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val caption = array.optJSONObject(i)?.optString("caption").orEmpty()
                if (caption.isNotBlank()) {
                    add(caption)
                }
            }
        }
    }

    fun scheduleType(kind: BusScheduleKind): String = when (kind) {
        BusScheduleKind.Weekday -> "weekday"
        BusScheduleKind.Saturday -> "saturday"
        BusScheduleKind.Holiday -> "holiday"
    }

    /** `regular[0].schedules` から当日種別の trips 配列を返す。 */
    fun tripsArrayForDate(root: JSONObject, date: LocalDate): JSONArray? {
        val regular = root.optJSONArray("regular") ?: return null
        if (regular.length() == 0) {
            return null
        }
        val schedules = regular.getJSONObject(0).optJSONArray("schedules") ?: return null
        val type = scheduleType(busScheduleKind(date))
        return (0 until schedules.length())
            .map { schedules.getJSONObject(it) }
            .firstOrNull { it.optString("scheduleType") == type }
            ?.optJSONArray("trips")
    }
}
