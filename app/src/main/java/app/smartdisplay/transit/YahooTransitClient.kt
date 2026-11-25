package app.smartdisplay.transit

import app.smartdisplay.util.NetworkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

class YahooTransitClient {
    suspend fun fetch(line: TrainLine): LineOperation = withContext(Dispatchers.IO) {
        val html = download(line.yahooId)
        parse(line, html)
    }

    private fun download(lineId: Int): String {
        val url = "https://transit.yahoo.co.jp/diainfo/$lineId/0"
        return NetworkLog.trace(url) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept-Language", "ja,en;q=0.8")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: error("Yahoo diainfo HTTP $code")
                }
                BufferedReader(InputStreamReader(stream, Charset.forName("UTF-8"))).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parse(line: TrainLine, html: String): LineOperation {
        val statusBlock = extractStatusBlock(html)
        val label = extractStatusLabel(statusBlock ?: html)
        val detail = extractDetail(statusBlock ?: html)
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
            .ifBlank { "詳細情報を取得できませんでした。" }
        val updatedAt = Regex("""(\d{1,2}月\d{1,2}日\s*\d{1,2}時\d{1,2}分更新)""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
        val kind = classify(label, detail, statusBlock ?: html)
        return LineOperation(
            line = line,
            kind = kind,
            statusLabel = label.ifBlank {
                when (kind) {
                    OperationKind.Normal -> "平常運転"
                    OperationKind.Delay -> "遅延"
                    OperationKind.Suspended -> "運転見合わせ"
                    OperationKind.Resumed -> "運転再開"
                    OperationKind.Other -> "運行情報あり"
                }
            },
            detail = detail,
            updatedAtText = updatedAt,
        )
    }

    private fun extractStatusBlock(html: String): String? {
        val patterns = listOf(
            Regex("""<div[^>]*id="mdServiceStatus"[^>]*>(.*?)</div>\s*<!--/#mdServiceStatus-->""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<div[^>]*class="[^"]*elmServiceStatus[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL),
            Regex("""<div[^>]*id="mdServiceStatus"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL),
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun extractStatusLabel(html: String): String {
        val dt = Regex("""<dt[^>]*>(.*?)</dt>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        return stripTags(dt)
            .replace("[!]", "")
            .replace("[○]", "")
            .trim()
    }

    private fun extractDetail(html: String): String? {
        val dd = Regex(
            """<dd[^>]*class="[^"]*(?:trouble|normal)[^"]*"[^>]*>\s*(?:<p>)?(.*?)(?:</p>)?\s*</dd>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html)?.groupValues?.getOrNull(1)
        if (dd != null) {
            return stripTags(dd)
        }
        val p = Regex("""<dd[^>]*>\s*<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
        return p?.let(::stripTags)
    }

    private fun classify(label: String, detail: String, html: String): OperationKind {
        val text = "$label $detail"
        return when {
            text.contains("見合わせ") -> OperationKind.Suspended
            text.contains("運転再開") || label.contains("再開") -> OperationKind.Resumed
            html.contains("""class="normal"""") || label.contains("平常") -> OperationKind.Normal
            text.contains("遅延") -> OperationKind.Delay
            html.contains("""class="trouble"""") -> OperationKind.Other
            else -> OperationKind.Other
        }
    }

    private fun stripTags(value: String): String {
        return value
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("､", "、")
            .trim()
    }
}
