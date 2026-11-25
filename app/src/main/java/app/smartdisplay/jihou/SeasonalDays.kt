package app.smartdisplay.jihou

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private data class DayName(
    val label: String,
    val speech: String,
)

/**
 * 日付の下と、3の倍数時（0時、3時、6時…）の時報で読む名前。
 * 祝日を先にし、そのあとに季節の行事を、指定の順で「、」でつなぐ。
 * 休日ダイヤや祝日の時報テーマには使わない。
 */
fun holidayAndSeasonalLabel(date: LocalDate): String? {
    return joinNames(holidayAndSeasonalParts(date).map { it.label })
}

/** 読み上げ用。祝日名はそのまま、行事は読みが分かれにくい言い方にする。 */
fun holidayAndSeasonalSpeech(date: LocalDate): String? {
    return joinNames(holidayAndSeasonalParts(date).map { it.speech })
}

/** 祝日と行事の表示名。同じ名前の記念日を案内から外すときに使う。 */
fun holidayAndSeasonalNames(date: LocalDate): List<String> {
    return holidayAndSeasonalParts(date).map { it.label }
}

private fun holidayAndSeasonalParts(date: LocalDate): List<DayName> {
    val parts = ArrayList<DayName>()
    japaneseHolidayName(date)?.let { parts.add(DayName(it, it)) }
    seasonalIn(date.year)[date]?.let(parts::addAll)
    return parts
}

data class UpcomingObservance(
    val name: String,
    val date: LocalDate,
    val holiday: Boolean,
)

/**
 * 今日以降で、祝日と行事それぞれの次の日。
 * 近い順。同じ日は祝日を先にする。振替休日は入れない。
 */
fun upcomingObservances(today: LocalDate): List<UpcomingObservance> {
    val found = LinkedHashMap<String, UpcomingFound>()
    var order = 0
    for (offset in 0..7) {
        val year = today.year + offset
        for ((date, name) in japaneseHolidaysOf(year).entries.sortedBy { it.key }) {
            if (date.isBefore(today) || name == "振替休日" || name in found) {
                continue
            }
            found[name] = UpcomingFound(name, date, holiday = true, order = order++)
        }
        for ((date, names) in seasonalIn(year).entries.sortedBy { it.key }) {
            if (date.isBefore(today)) {
                continue
            }
            for (day in names) {
                if (day.label in found) {
                    continue
                }
                found[day.label] = UpcomingFound(day.label, date, holiday = false, order = order++)
            }
        }
    }
    return found.values.sortedWith(
        compareBy<UpcomingFound> { it.date }.thenBy { !it.holiday }.thenBy { it.order },
    ).map { UpcomingObservance(it.name, it.date, it.holiday) }
}

private data class UpcomingFound(
    val name: String,
    val date: LocalDate,
    val holiday: Boolean,
    val order: Int,
)

private fun joinNames(names: List<String>): String? {
    if (names.isEmpty()) {
        return null
    }
    return names.joinToString("、")
}

private val seasonalByYear = HashMap<Int, Map<LocalDate, List<DayName>>>()

private fun seasonalIn(year: Int): Map<LocalDate, List<DayName>> {
    synchronized(seasonalByYear) {
        seasonalByYear[year]?.let { return it }
        val built = buildSeasonal(year)
        seasonalByYear[year] = built
        return built
    }
}

private fun buildSeasonal(year: Int): Map<LocalDate, List<DayName>> {
    val dates = LinkedHashMap<LocalDate, MutableList<DayName>>()
    fun add(date: LocalDate, label: String, speech: String = label) {
        dates.getOrPut(date) { mutableListOf() }.add(DayName(label, speech))
    }
    fun addRange(start: LocalDate, endInclusive: LocalDate, label: String, speech: String) {
        var day = start
        while (!day.isAfter(endInclusive)) {
            add(day, label, speech)
            day = day.plusDays(1)
        }
    }
    val risshun = solarTermDate(315.0, LocalDate.of(year, 2, 4))
    val springEquinox = vernalEquinoxDate(year)
    val autumnEquinox = autumnalEquinoxDate(year)
    add(LocalDate.of(year, 1, 7), "七草", "ななくさ")
    add(LocalDate.of(year, 1, 11), "鏡開き", "かがみびらき")
    add(LocalDate.of(year, 1, 15), "小正月", "こしょうがつ")
    add(risshun.minusDays(1), "節分", "せつぶん")
    add(LocalDate.of(year, 2, 14), "バレンタインデー")
    add(LocalDate.of(year, 3, 3), "ひな祭り", "ひなまつり")
    add(LocalDate.of(year, 3, 14), "ホワイトデー")
    addRange(springEquinox.minusDays(3), springEquinox.plusDays(3), "彼岸", "ひがん")
    add(LocalDate.of(year, 4, 1), "エイプリルフール")
    add(westernEaster(year), "イースター")
    add(risshun.plusDays(87), "八十八夜", "はちじゅうはちや")
    add(nthSunday(year, 5, 2), "母の日", "ははのひ")
    add(solarTermDate(80.0, LocalDate.of(year, 6, 11)), "入梅", "にゅうばい")
    add(nthSunday(year, 6, 3), "父の日", "ちちのひ")
    add(solarTermDate(90.0, LocalDate.of(year, 6, 21)), "夏至", "げし")
    add(LocalDate.of(year, 6, 30), "夏越の祓", "なごしのはらえ")
    add(solarTermDate(100.0, LocalDate.of(year, 7, 2)), "半夏生", "はんげしょう")
    add(LocalDate.of(year, 7, 7), "七夕", "たなばた")
    addSummerOxDays(year) { date, label, speech ->
        add(date, label, speech)
    }
    addRange(LocalDate.of(year, 8, 13), LocalDate.of(year, 8, 16), "お盆", "おぼん")
    add(LocalDate.of(year, 8, 15), "終戦記念日", "しゅうせんきねんび")
    add(LocalDate.of(year, 9, 1), "防災の日", "ぼうさいのひ")
    addRange(autumnEquinox.minusDays(3), autumnEquinox.plusDays(3), "彼岸", "ひがん")
    add(harvestMoonDate(year), "十五夜", "じゅうごや")
    add(LocalDate.of(year, 10, 31), "ハロウィーン")
    add(LocalDate.of(year, 11, 15), "七五三", "しちごさん")
    add(solarTermDate(270.0, LocalDate.of(year, 12, 22)), "冬至", "とうじ")
    add(LocalDate.of(year, 12, 24), "クリスマス・イヴ", "クリスマスイブ")
    add(LocalDate.of(year, 12, 25), "クリスマス")
    add(LocalDate.of(year, 12, 31), "大晦日", "おおみそか")
    return dates
}

/** 夏の土用（太陽黄経 117 度の日から立秋の前日まで）にある丑の日。2回ある年はどちらも同じ名前。 */
private fun addSummerOxDays(year: Int, add: (LocalDate, String, String) -> Unit) {
    val start = solarTermDate(117.0, LocalDate.of(year, 7, 20))
    val risshu = solarTermDate(135.0, LocalDate.of(year, 8, 7))
    var day = start
    while (day.isBefore(risshu)) {
        if (isOxDay(day)) {
            add(day, "土用の丑の日", "どようのうしのひ")
        }
        day = day.plusDays(1)
    }
}

/** 2024-07-24 は丑。日の十二支は 12 日周期。 */
private fun isOxDay(date: LocalDate): Boolean {
    return ChronoUnit.DAYS.between(OxDayAnchor, date) % 12L == 0L
}

private val OxDayAnchor: LocalDate = LocalDate.of(2024, 7, 24)

private fun nthSunday(year: Int, month: Int, n: Int): LocalDate {
    return LocalDate.of(year, month, 1)
        .with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.SUNDAY))
}

/** 西方教会のイースター（春分のあとの最初の満月の次の日曜、グレゴリオ暦）。 */
private fun westernEaster(year: Int): LocalDate {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = (h + l - 7 * m + 114) % 31 + 1
    return LocalDate.of(year, month, day)
}
