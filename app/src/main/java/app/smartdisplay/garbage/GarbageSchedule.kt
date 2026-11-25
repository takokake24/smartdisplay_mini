package app.smartdisplay.garbage

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

enum class GarbageKind(val label: String) {
    Burnable("燃やすごみ"),
    NonBurnable("燃やさないごみ"),
    Plastic("プラスチック製容器包装"),
    Can("缶"),
    Bottle("ビン"),
    PetBottle("ペットボトル"),
    Hazardous("有害性資源"),
    Metal("金属製品"),
    Newspaper("新聞紙"),
    Cardboard("段ボール"),
    Magazine("雑誌・本・雑がみ・シュレッダーくず"),
    Futon("ふとん"),
    Cloth("古布類"),
}

private val NonBurnableAnchor = LocalDate.of(2026, 11, 10)
private val CanAnchor = LocalDate.of(2026, 11, 10)
private val BottleAnchor = LocalDate.of(2026, 11, 17)
private val PetBottleAnchor = LocalDate.of(2026, 11, 20)
private val PaperAnchor = LocalDate.of(2026, 11, 11)
private val MagazineAnchor = LocalDate.of(2026, 11, 18)

private val WeekdayLabels = listOf("月", "火", "水", "木", "金", "土", "日")

/** 収集日のこの時刻未満は「今日」。この時刻からは次の収集日。 */
const val GarbageTodayCutoffHour = 9

fun garbageKindsOn(date: LocalDate): List<GarbageKind> {
    return GarbageKind.entries.filter { collectsOn(it, date) }
}

/** 毎年 12/30〜1/3 は収集しない。 */
fun isGarbageRestDay(date: LocalDate): Boolean {
    val month = date.monthValue
    val day = date.dayOfMonth
    return (month == 12 && day >= 30) || (month == 1 && day <= 3)
}

fun collectsOn(kind: GarbageKind, date: LocalDate): Boolean {
    if (isGarbageRestDay(date)) {
        return false
    }
    return when (kind) {
        GarbageKind.Burnable ->
            date.dayOfWeek == DayOfWeek.MONDAY || date.dayOfWeek == DayOfWeek.THURSDAY
        GarbageKind.NonBurnable ->
            date.dayOfWeek == DayOfWeek.TUESDAY && matchesInterval(date, NonBurnableAnchor, 28)
        GarbageKind.Plastic ->
            date.dayOfWeek == DayOfWeek.FRIDAY
        GarbageKind.Can ->
            date.dayOfWeek == DayOfWeek.TUESDAY && matchesInterval(date, CanAnchor, 14)
        GarbageKind.Bottle,
        GarbageKind.Hazardous,
        GarbageKind.Metal,
        -> date.dayOfWeek == DayOfWeek.TUESDAY && matchesInterval(date, BottleAnchor, 14)
        GarbageKind.PetBottle ->
            date.dayOfWeek == DayOfWeek.FRIDAY && matchesInterval(date, PetBottleAnchor, 14)
        GarbageKind.Newspaper,
        GarbageKind.Cardboard,
        -> date.dayOfWeek == DayOfWeek.WEDNESDAY && matchesInterval(date, PaperAnchor, 14)
        GarbageKind.Magazine,
        GarbageKind.Futon,
        GarbageKind.Cloth,
        -> date.dayOfWeek == DayOfWeek.WEDNESDAY && matchesInterval(date, MagazineAnchor, 14)
    }
}

/**
 * 一覧に出す次の収集日。
 * 当日かつ 9:00 未満ならその日。9:00 以降はそれより後の収集日。
 */
fun nextCollectionDate(kind: GarbageKind, now: LocalDateTime): LocalDate {
    val today = now.toLocalDate()
    if (now.hour < GarbageTodayCutoffHour && collectsOn(kind, today)) {
        return today
    }
    var cursor = today.plusDays(1)
    repeat(70) {
        if (collectsOn(kind, cursor)) {
            return cursor
        }
        cursor = cursor.plusDays(1)
    }
    return cursor
}

fun garbageDaySpeech(kinds: List<GarbageKind>, tomorrow: Boolean): String {
    val whenWord = if (tomorrow) "明日" else "今日"
    val names = kinds.joinToString("、") { it.label }
    return "${whenWord}のごみ収集は、${names}です。"
}

fun garbageWeekdayLabel(date: LocalDate): String {
    return WeekdayLabels[date.dayOfWeek.value - 1]
}

fun garbageDateLabel(date: LocalDate): String {
    return "${date.monthValue}/${date.dayOfMonth}（${garbageWeekdayLabel(date)}）"
}

private fun matchesInterval(date: LocalDate, anchor: LocalDate, intervalDays: Long): Boolean {
    val days = ChronoUnit.DAYS.between(anchor, date)
    return days % intervalDays == 0L
}
