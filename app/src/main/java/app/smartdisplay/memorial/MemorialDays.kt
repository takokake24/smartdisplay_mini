package app.smartdisplay.memorial

import app.smartdisplay.jihou.holidayAndSeasonalNames
import app.smartdisplay.util.NetworkLog
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** 時報で読み上げる件数。超えた分は件数だけ読み、画面の一覧はこの件数では切らない。 */
private const val MemorialSpeechLimit = 10

private const val ListUrl = "https://www.kinenbi.gr.jp/"
private val TimeoutMs = TimeUnit.SECONDS.toMillis(12)

private val DateLabel = Regex("""today_dayboxday">\s*([0-9０-９]+)月([0-9０-９]+)日""")
private val CertifiedLink = Regex(
    """<a\b[^>]*href="yurai\.php\?TYPE=ofi[^"]*?NM=(\d+)[^"]*"[^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val DetailParagraph = Regex(
    """<p\b[^>]*>(.*?)</p>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val BreakTag = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val Tag = Regex("<[^>]+>")
private val NumericEntity = Regex("&#(\\d+);")
private val HexEntity = Regex("&#x([0-9a-fA-F]+);")
private val BracketNote = Regex("[<＜][^<>＜＞]*[>＞]")
private val Parenthetical = Regex("[（(][^（）()]*[）)]")
private val SlashMonth = Regex("""\s*[/／]\s*[^/／]*月(?:分)?\s*$""")
private val Whitespace = Regex("\\s+")
private val InlineSpace = Regex("[ \\t\\u3000]+")

data class MemorialDay(
    val id: String,
    val name: String,
) {
    val listLabel: String
        get() = memorialListLabel(name)

    /** 期間や毎月○日など、一覧には出さない注記。 */
    val scheduleNote: String
        get() = memorialScheduleNote(name)
}

/**
 * 0時・12時の時報で案内する。
 * 日本記念日協会のページに出ている認定記念日の順（新しい登録が先）を上位とする。
 * 前日にもあった記念日は、その順のまま後ろへ下げる。
 */
fun memorialsForChime(timeHour: Int, timeMinute: Int): Boolean {
    return timeMinute == 0 && (timeHour == 0 || timeHour == 12)
}

/**
 * 祝日や季節の行事と同じ名前の記念日は案内から外す。メニューの一覧には残す。
 * [previous] は前日の認定記念日。同じものは連日・月間なので優先度を下げる。
 */
fun announcementMemorials(
    date: LocalDate,
    days: List<MemorialDay>,
    previous: List<MemorialDay> = emptyList(),
): List<MemorialDay> {
    val blocked = holidayAndSeasonalNames(date).map(::memorialIdentity).filter { it.isNotEmpty() }.toSet()
    val visible = if (blocked.isEmpty()) {
        days
    } else {
        days.filter { memorialIdentity(it.name) !in blocked }
    }
    return demoteContinuingMemorials(visible, previous)
}

/** 前日と同じ記念日は後ろへ下げ、その日だけのものを先に案内する。 */
private fun demoteContinuingMemorials(
    days: List<MemorialDay>,
    previous: List<MemorialDay>,
): List<MemorialDay> {
    if (days.isEmpty() || previous.isEmpty()) {
        return days
    }
    val continuing = previous.mapTo(HashSet()) { it.id }
    val (fresh, repeated) = days.partition { it.id !in continuing }
    return fresh + repeated
}

fun memorialSpeech(days: List<MemorialDay>): String {
    val spoken = days.take(MemorialSpeechLimit).joinToString("、") { it.listLabel }
    val speech = "今日は、${spoken}です。"
    val rest = days.size - MemorialSpeechLimit
    if (rest <= 0) {
        return speech
    }
    return "${speech}そのほかに、${rest}個の記念日が登録されています。"
}

/** 期間の括弧や「／○月分」を外した、一覧と読み上げ用の名前。 */
fun memorialListLabel(name: String): String {
    val withoutNotes = BracketNote.replace(name, "")
    val withoutSpans = Parenthetical.replace(withoutNotes) { match ->
        if (isDateSpan(unwrap(match.value))) "" else match.value
    }
    val label = SlashMonth.replace(withoutSpans, "")
        .replace(InlineSpace, " ")
        .trim()
    return label.ifEmpty { name.trim() }
}

/** 一覧から外した期間・毎月の注記。説明ではこちらを出す。 */
fun memorialScheduleNote(name: String): String {
    val notes = mutableListOf<String>()
    BracketNote.findAll(name).forEach { match ->
        unwrap(match.value).takeIf { it.isNotEmpty() }?.let(notes::add)
    }
    Parenthetical.findAll(name).forEach { match ->
        val inner = unwrap(match.value)
        if (inner.isNotEmpty() && isDateSpan(inner)) {
            notes.add(inner)
        }
    }
    val slash = SlashMonth.find(name)?.value
        ?.trim()
        ?.trimStart('/', '／')
        ?.trim()
        .orEmpty()
    val body = notes.joinToString("、")
    return when {
        body.isEmpty() -> slash
        slash.isEmpty() -> body
        else -> "$body／$slash"
    }
}

/**
 * 協会サイトの認定記念日。月日だけを問い合わせる（移動日は協会が当年分を掲載している）。
 */
private fun fetchCertifiedMemorials(date: LocalDate): List<MemorialDay> {
    val url = "$ListUrl?MD=1&M=${date.monthValue}&D=${date.dayOfMonth}"
    return NetworkLog.trace(url) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TimeoutMs.toInt()
            readTimeout = TimeoutMs.toInt()
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", "smartdisplay")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("記念日 HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseCertifiedMemorials(body, date.monthValue, date.dayOfMonth)
        } finally {
            connection.disconnect()
        }
    }
}

private fun fetchMemorialDetail(id: String): String {
    val url = "${ListUrl}yurai.php?TYPE=ofi&MD=3&NM=$id"
    return NetworkLog.trace(url) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TimeoutMs.toInt()
            readTimeout = TimeoutMs.toInt()
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html")
            setRequestProperty("User-Agent", "smartdisplay")
            setRequestProperty("Referer", ListUrl)
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            parseMemorialDetail(body) ?: error("記念日の説明がありません")
        } finally {
            connection.disconnect()
        }
    }
}

private fun parseMemorialDetail(html: String): String? {
    val text = DetailParagraph.findAll(html).map { match ->
        val raw = BreakTag.replace(match.groupValues[1], "\n")
        cleanDetail(decodeHtml(Tag.replace(raw, "")))
    }.filter { it.isNotEmpty() }.joinToString("\n\n")
    return text.ifBlank { null }
}

private fun parseCertifiedMemorials(html: String, month: Int, day: Int): List<MemorialDay> {
    val label = DateLabel.find(html) ?: error("記念日の日付がありません")
    val pageMonth = label.groupValues[1].toHalfWidthInt()
    val pageDay = label.groupValues[2].toHalfWidthInt()
    if (pageMonth != month || pageDay != day) {
        error("記念日の日付が一致しません")
    }
    val start = html.indexOf("today_kinenbibox01")
    val end = if (start >= 0) html.indexOf("today_kinenbibox02", start + 1) else -1
    if (start < 0 || end < 0) {
        error("記念日の一覧がありません")
    }
    val section = html.substring(start, end)
    return CertifiedLink.findAll(section).map { match ->
        val name = decodeHtml(Tag.replace(match.groupValues[2], "")).trim()
        MemorialDay(id = match.groupValues[1], name = name)
    }.filter { it.name.isNotEmpty() }.toList()
}

class MemorialRepository {
    private val cache = HashMap<Int, List<MemorialDay>>()
    private val details = HashMap<String, String>()
    private var cacheYear: Int = 0

    fun cached(date: LocalDate, today: LocalDate = LocalDate.now()): List<MemorialDay>? {
        synchronized(cache) {
            if (cacheYear != today.year) {
                return null
            }
            return cache[key(date)]
        }
    }

    fun cachedDetail(id: String): String? {
        synchronized(details) {
            return details[id]
        }
    }

    fun load(
        date: LocalDate,
        force: Boolean = false,
        today: LocalDate = LocalDate.now(),
    ): List<MemorialDay> {
        if (!force) {
            cached(date, today)?.let { return it }
        }
        val names = fetchCertifiedMemorials(date)
        synchronized(cache) {
            if (cacheYear != today.year) {
                cache.clear()
                cacheYear = today.year
            }
            cache[key(date)] = names
        }
        return names
    }

    fun loadDetail(id: String, force: Boolean = false): String {
        if (!force) {
            cachedDetail(id)?.let { return it }
        }
        val text = fetchMemorialDetail(id)
        synchronized(details) {
            details[id] = text
        }
        return text
    }

    private fun key(date: LocalDate): Int = date.monthValue * 32 + date.dayOfMonth
}

private fun isDateSpan(text: String): Boolean {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
    if (!normalized.contains("月")) {
        return false
    }
    return normalized.contains("日") ||
        normalized.contains("から") ||
        normalized.contains("まで") ||
        normalized.contains("~") ||
        normalized.contains("～")
}

private fun unwrap(wrapped: String): String {
    return wrapped.trim().removeSurrounding("＜", "＞")
        .removeSurrounding("<", ">")
        .removeSurrounding("（", "）")
        .removeSurrounding("(", ")")
        .trim()
}

private fun cleanDetail(text: String): String {
    return text
        .replace(InlineSpace, " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
}

private fun memorialIdentity(name: String): String {
    val stripped = name.replace(BracketNote, "").replace(Parenthetical, "").trim()
    val source = stripped.ifEmpty { name.trim() }
    return Normalizer.normalize(source, Normalizer.Form.NFKC)
        .replace(Whitespace, "")
}

private fun decodeHtml(text: String): String {
    val decimal = NumericEntity.replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
    }
    val hex = HexEntity.replace(decimal) { match ->
        match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
    }
    return hex
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}

private fun String.toHalfWidthInt(): Int {
    return Normalizer.normalize(this, Normalizer.Form.NFKC).toInt()
}
