package app.smartdisplay.bus

import app.smartdisplay.util.NetworkLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalTime

/**
 * NAVITIME 公開ダイヤ（`/diagram/bus/{node}/{line}/{dir}/`）の共通パーサ。
 *
 * Android のデフォルト UA（Dalvik）だと 403、モバイル UA だと時刻表なしの
 * スマホ誘導ページになるため、PC Chrome 相当のヘッダで取得する。
 */
internal object NavitimePublicDiagram {
    private const val DesktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun download(url: String): String = NetworkLog.trace(url) {
        // 一部端末で HttpURLConnection が UA を上書きするため、両方指定する
        val previousAgent = System.getProperty("http.agent")
        System.setProperty("http.agent", DesktopUserAgent)
        try {
            // /0/ → http://…/1/ の 301 があり、Android は cleartext で失敗するため
            // リダイレクトは手動で追い、http は https に上げる
            var current = url
            repeat(5) {
                val connection = openConnection(current)
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: error("Navitime diagram HTTP $code (no Location)")
                        current = upgradeToHttps(resolveRedirect(current, location))
                        return@repeat
                    }
                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream ?: error("Navitime diagram HTTP $code")
                    }
                    val html = BufferedReader(InputStreamReader(stream, Charset.forName("UTF-8")))
                        .use { it.readText() }
                    if (isSmartphoneShellPage(html)) {
                        error("Navitime diagram returned smartphone shell page")
                    }
                    return@trace html
                } finally {
                    connection.disconnect()
                }
            }
            error("Navitime diagram too many redirects")
        } finally {
            if (previousAgent != null) {
                System.setProperty("http.agent", previousAgent)
            } else {
                System.clearProperty("http.agent")
            }
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", DesktopUserAgent)
            setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            setRequestProperty("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Referer", "https://www.navitime.co.jp/bus/diagram/")
            setRequestProperty("Upgrade-Insecure-Requests", "1")
            setRequestProperty("sec-ch-ua-mobile", "?0")
            setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
        }

    private fun resolveRedirect(current: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return location
        }
        return URL(URL(current), location).toString()
    }

    private fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://")) "https://${url.removePrefix("http://")}" else url

    data class DepartureEntry(
        val time: LocalTime,
        /** `arrive_station_name` の印（例: 明）。無印は空文字。 */
        val arriveMark: String = "",
        /** その便の停車停留所ページ。無いときは空。 */
        val stopsUrl: String = "",
    )

    fun parseDepartures(html: String, kind: BusScheduleKind): List<LocalTime> =
        parseDepartureEntries(html, kind).map { it.time }.distinct().sorted()

    fun parseDepartureEntries(html: String, kind: BusScheduleKind): List<DepartureEntry> {
        val dlClass = when (kind) {
            BusScheduleKind.Weekday -> "dl_0"
            BusScheduleKind.Saturday -> "dl_1"
            BusScheduleKind.Holiday -> "dl_2"
        }
        val out = mutableListOf<DepartureEntry>()
        val blocks = Regex(
            """<dl class="$dlClass">\s*<dt>(\d{1,2})</dt>\s*<dd>(.*?)</dd>\s*</dl>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html)
        val anchorPattern = Regex(
            """<a href="([^"]*diagram/stops/[^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val markedMinute = Regex(
            """<div style="text-align: left;text-decoration: underline;">(\d{1,2})</div>\s*""" +
                """<div class="arrive_station_name">\s*([^<]*)</div>""",
        )
        val minuteOnlyPattern = Regex(
            """<div style="text-align: left;text-decoration: underline;">(\d{1,2})</div>""",
        )
        for (block in blocks) {
            // 24・25時台は 0・1 時に正規化し、atBusTime で翌日へ回す
            val hour = block.groupValues[1].toInt() % 24
            val dd = block.groupValues[2]
            val anchors = anchorPattern.findAll(dd).toList()
            if (anchors.isNotEmpty()) {
                for (anchor in anchors) {
                    val inner = anchor.groupValues[2]
                    val marked = markedMinute.find(inner)
                    val minute = marked?.groupValues?.get(1)?.toIntOrNull()
                        ?: minuteOnlyPattern.find(inner)?.groupValues?.get(1)?.toIntOrNull()
                        ?: continue
                    if (minute !in 0..59) {
                        continue
                    }
                    out += DepartureEntry(
                        time = LocalTime.of(hour, minute),
                        arriveMark = marked?.groupValues?.get(2)?.trim().orEmpty(),
                        stopsUrl = absoluteStopsUrl(anchor.groupValues[1]),
                    )
                }
            } else {
                val matched = markedMinute.findAll(dd).toList()
                if (matched.isNotEmpty()) {
                    for (m in matched) {
                        val minute = m.groupValues[1].toInt()
                        if (minute !in 0..59) {
                            continue
                        }
                        out += DepartureEntry(
                            time = LocalTime.of(hour, minute),
                            arriveMark = m.groupValues[2].trim(),
                        )
                    }
                } else {
                    for (m in minuteOnlyPattern.findAll(dd)) {
                        val minute = m.groupValues[1].toInt()
                        if (minute !in 0..59) {
                            continue
                        }
                        out += DepartureEntry(time = LocalTime.of(hour, minute))
                    }
                }
            }
        }
        return out
            .distinctBy { "${it.time}|${it.arriveMark}" }
            .sortedWith(compareBy({ it.time }, { it.arriveMark }))
    }

    fun diagramUrl(nodeId: String, lineId: String, direction: Int): String =
        "https://www.navitime.co.jp/diagram/bus/$nodeId/$lineId/$direction/"

    /** 便ページの停留所名と発着。 */
    fun parseStops(html: String): List<BusStop> {
        val pattern = Regex(
            """<dt class="station-name">\s*<a[^>]*>([^<]+)</a>\s*</dt>\s*""" +
                """<dd class="time">\s*(\d{1,2}:\d{2})(発|着)\s*</dd>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val out = mutableListOf<BusStop>()
        for (match in pattern.findAll(html)) {
            val name = unescapeHtml(match.groupValues[1])
            if (name.isEmpty()) {
                continue
            }
            val time = parseClock(match.groupValues[2]) ?: continue
            val departing = match.groupValues[3] == "発"
            val stop = if (departing) {
                BusStop(name = name, departure = time)
            } else {
                BusStop(name = name, arrival = time)
            }
            val previous = out.lastOrNull()
            if (previous != null && previous.name == name) {
                out[out.lastIndex] = previous.copy(
                    arrival = previous.arrival ?: stop.arrival,
                    departure = previous.departure ?: stop.departure,
                )
            } else {
                out += stop
            }
        }
        if (out.isEmpty()) {
            error("停車停留所がありません")
        }
        return out
    }

    private fun absoluteStopsUrl(href: String): String {
        val cleaned = href.replace("&amp;", "&").trim()
        return when {
            cleaned.startsWith("https://") -> cleaned
            cleaned.startsWith("http://") -> "https://${cleaned.removePrefix("http://")}"
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "https://www.navitime.co.jp$cleaned"
            else -> "https://www.navitime.co.jp/$cleaned"
        }
    }

    private fun parseClock(text: String): LocalTime? {
        val parts = text.split(":")
        if (parts.size != 2) {
            return null
        }
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..47 || minute !in 0..59) {
            return null
        }
        return LocalTime.of(hour % 24, minute)
    }

    private fun unescapeHtml(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()

    private fun isSmartphoneShellPage(html: String): Boolean {
        if ("timeTable" in html || """class="dl_0"""" in html || """class='dl_0'""" in html) {
            return false
        }
        return "sp/diagram/bus" in html || "smartapp.diagram.bus" in html
    }
}
