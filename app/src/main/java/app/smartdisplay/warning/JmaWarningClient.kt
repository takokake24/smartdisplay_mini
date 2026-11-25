package app.smartdisplay.warning

import app.smartdisplay.util.NetworkLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/**
 * 気象庁ホームページの警報・注意報 JSON（r8）から小平市分を読む。
 */
class JmaWarningClient {
    fun fetchKodairaWarnings(): List<WeatherWarningItem> =
        NetworkLog.trace(TokyoWarningUrl) {
            val connection = (URL(TokyoWarningUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TimeoutMs.toInt()
                readTimeout = TimeoutMs.toInt()
                requestMethod = "GET"
                setRequestProperty("User-Agent", "smartdisplay")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("jma warning http $code")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseActiveForArea(body, KodairaAreaCode)
            } finally {
                connection.disconnect()
            }
        }

    fun parseActiveForArea(body: String, areaCode: String): List<WeatherWarningItem> {
        val root = JSONArray(body)
        val byCode = linkedMapOf<String, WeatherWarningItem>()
        for (i in 0 until root.length()) {
            val report = root.getJSONObject(i)
            val reportAt = parseReportAt(report.optString("reportDatetime"))
            val warning = report.optJSONObject("warning") ?: continue
            val items = warning.optJSONArray("class20Items") ?: continue
            for (j in 0 until items.length()) {
                val area = items.getJSONObject(j)
                if (area.optString("areaCode") != areaCode) {
                    continue
                }
                val kinds = area.optJSONArray("kinds") ?: continue
                for (k in 0 until kinds.length()) {
                    val kindObj = kinds.getJSONObject(k)
                    val status = kindObj.optString("status")
                    if (status != "発表" && status != "継続") {
                        continue
                    }
                    val code = kindObj.optString("code")
                    val kind = weatherWarningKindOf(code) ?: continue
                    val details = parseAdditions(kindObj.optJSONArray("additions"))
                    byCode[code] = WeatherWarningItem(
                        kind = kind,
                        status = status,
                        reportAt = reportAt,
                        detailTexts = details,
                    )
                }
            }
        }
        return byCode.values
            .sortedWith(
                compareByDescending<WeatherWarningItem> { it.severity.ordinal }
                    .thenBy { it.name },
            )
    }

    private fun parseAdditions(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }
        return buildList {
            for (i in 0 until array.length()) {
                val text = array.optString(i).trim()
                if (text.isNotEmpty()) {
                    add(text)
                }
            }
        }
    }

    private fun parseReportAt(raw: String): LocalDateTime? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching {
            OffsetDateTime.parse(raw).toLocalDateTime()
        }.getOrNull()
    }

    companion object {
        const val KodairaAreaCode = "1321100"
        private const val TokyoWarningUrl =
            "https://www.jma.go.jp/bosai/warning/data/r8/130000.json"
        private val TimeoutMs = TimeUnit.SECONDS.toMillis(12)
    }
}
