package app.smartdisplay.bus

import androidx.compose.ui.graphics.Color
import app.smartdisplay.jihou.isJapanesePublicHoliday
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class BusCompany(
    val displayName: String,
    val accent: Color,
) {
    Keio("京王バス", Color(0xFFE91E8C)),
    Tachikawa("立川バス", Color(0xFFCC0000)),
    Seibu("西武バス", Color(0xFF0072BC)),
    Ginga("銀河鉄道バス", Color(0xFF5B2C8B)),
    Community("国分寺市コミュニティバス", Color(0xFF2E7D32)),
    Kanto("関東バス", Color(0xFFE65100)),
    KoganeiCommunity("小金井市コミュニティバス", Color(0xFF00897B)),
    TokyoAirportTransport("東京空港交通", Color(0xFF0D47A1)),
    FujiQ("富士急バス", Color(0xFFC62828)),
    Kintetsu("近鉄バス", Color(0xFFAD1457)),
    JrBusKanto("JRバス関東", Color(0xFF2E7D32)),
    Highway("高速バス", Color(0xFF455A64)),
}

enum class BusDirection(
    val tabLabel: String,
) {
    KokubunjiMusashiKoganei("国分寺・武蔵小金井方面"),
    HanakoganeiShowa("花小金井・昭和病院方面"),
    HitotsubashiKodaira("一橋学園・小平方面"),
    KokubunjiDepart("国分寺駅発"),
    MusashiKoganeiDepart("武蔵小金井駅発"),
    ChuoDoFuchuDepart("中央道府中発"),
}

/**
 * 方面タブ内の経由地絞り込み。
 * [matchNames] にバス停名が含まれる便を対象にし、表示名は [preferName] があればそれを優先する。
 */
enum class BusViaFilter(
    val direction: BusDirection,
    val filterLabel: String,
    val speechLabel: String,
    val matchNames: List<String>,
    val preferName: String? = null,
) {
    KokubunjiNorth(BusDirection.KokubunjiMusashiKoganei, "国分寺駅北口", "国分寺駅北口", listOf("国分寺駅北口", "国分寺北口"), "国分寺駅北口"),
    KokubunjiSouth(BusDirection.KokubunjiMusashiKoganei, "国分寺駅南口", "国分寺駅南口", listOf("国分寺駅南口"), "国分寺駅南口"),
    MusashiKoganeiNorth(BusDirection.KokubunjiMusashiKoganei, "武蔵小金井駅北口", "武蔵小金井駅北口", listOf("武蔵小金井駅北口"), "武蔵小金井駅北口"),
    HanakoganeiStation(BusDirection.HanakoganeiShowa, "花小金井駅", "花小金井駅", listOf("花小金井駅南口", "花小金井駅北口")),
    ShowaHospital(BusDirection.HanakoganeiShowa, "昭和病院", "昭和病院", listOf("昭和病院", "昭和病院前"), "昭和病院"),
    HitotsubashiStation(BusDirection.HitotsubashiKodaira, "一橋学園駅", "一橋学園駅", listOf("一橋学園駅"), "一橋学園駅"),
    OmeKaidoStation(BusDirection.HitotsubashiKodaira, "青梅街道駅", "青梅街道駅", listOf("青梅街道駅"), "青梅街道駅"),
    KodairaStation(BusDirection.HitotsubashiKodaira, "小平駅", "小平駅", listOf("小平駅南口"), "小平駅南口"),
    CityHall(BusDirection.HitotsubashiKodaira, "小平市役所", "小平市役所", listOf("小平市役所"), "小平市役所"),
    ShinKodairaStation(BusDirection.HitotsubashiKodaira, "新小平駅", "新小平駅", listOf("新小平駅"), "新小平駅"),
    DepartMalvern(BusDirection.KokubunjiDepart, "マルバーン", "マルバーン", listOf("マルバーン・カレッジ東京", "上水南町"), "マルバーン・カレッジ東京"),
    DepartMusashiKoganei(BusDirection.KokubunjiDepart, "武蔵小金井駅", "武蔵小金井駅", listOf("武蔵小金井駅北口"), "武蔵小金井駅北口"),
    DepartHanakoganei(BusDirection.KokubunjiDepart, "花小金井駅", "花小金井駅", listOf("花小金井駅南口", "花小金井駅北口")),
    DepartShowaHospital(BusDirection.KokubunjiDepart, "昭和病院", "昭和病院", listOf("昭和病院", "昭和病院前"), "昭和病院"),
    DepartHitotsubashi(BusDirection.KokubunjiDepart, "一橋学園駅", "一橋学園駅", listOf("一橋学園駅"), "一橋学園駅"),
    DepartOmeKaido(BusDirection.KokubunjiDepart, "青梅街道駅", "青梅街道駅", listOf("青梅街道駅"), "青梅街道駅"),
    DepartKodaira(BusDirection.KokubunjiDepart, "小平駅", "小平駅", listOf("小平駅南口"), "小平駅南口"),
    DepartCityHall(BusDirection.KokubunjiDepart, "小平市役所", "小平市役所", listOf("小平市役所"), "小平市役所"),
    DepartShinKodaira(BusDirection.KokubunjiDepart, "新小平駅", "新小平駅", listOf("新小平駅"), "新小平駅"),
    DepartTakanodai(BusDirection.KokubunjiDepart, "鷹の台駅", "鷹の台駅", listOf("鷹の台駅入口"), "鷹の台駅入口"),
    DepartNishiKokubunji(BusDirection.KokubunjiDepart, "西国分寺駅", "西国分寺駅", listOf("西国分寺駅東"), "西国分寺駅東"),
    DepartMedicalCenter(BusDirection.KokubunjiDepart, "総合医療ｾﾝﾀｰ", "総合医療センター", listOf("総合医療センター"), "総合医療センター"),
    DepartFuchu(BusDirection.KokubunjiDepart, "府中駅", "府中駅", listOf("府中駅"), "府中駅"),
    DepartKitaFuchu(BusDirection.KokubunjiDepart, "北府中駅", "北府中駅", listOf("東芝前"), "東芝前"),
    DepartHanedaT2(BusDirection.KokubunjiDepart, "羽田空港T2", "羽田空港第２ターミナル", listOf("羽田空港第２ターミナル"), "羽田空港第２ターミナル"),
    DepartHanedaT1(BusDirection.KokubunjiDepart, "羽田空港T1", "羽田空港第１ターミナル", listOf("羽田空港第１ターミナル"), "羽田空港第１ターミナル"),
    DepartHanedaT3(BusDirection.KokubunjiDepart, "羽田空港T3", "羽田空港第３ターミナル", listOf("羽田空港第３ターミナル"), "羽田空港第３ターミナル"),
    MkDepartMalvern(BusDirection.MusashiKoganeiDepart, "マルバーン", "マルバーン", listOf("マルバーン・カレッジ東京"), "マルバーン・カレッジ東京"),
    MkDepartKokubunji(BusDirection.MusashiKoganeiDepart, "国分寺駅", "国分寺駅", listOf("国分寺駅北口"), "国分寺駅北口"),
    MkDepartKaita(BusDirection.MusashiKoganeiDepart, "回田町", "回田町", listOf("回田町"), "回田町"),
    MkDepartKoganeiBridge(BusDirection.MusashiKoganeiDepart, "小金井橋", "小金井橋", listOf("小金井橋"), "小金井橋"),
    MkDepartHitotsubashi(BusDirection.MusashiKoganeiDepart, "一橋学園駅", "一橋学園駅", listOf("一橋学園駅"), "一橋学園駅"),
    MkDepartKodaira(BusDirection.MusashiKoganeiDepart, "小平駅", "小平駅", listOf("小平駅南口"), "小平駅南口"),
    MkDepartHanakoganei(BusDirection.MusashiKoganeiDepart, "花小金井駅", "花小金井駅", listOf("花小金井駅入口", "花小金井駅"), "花小金井駅入口"),
    MkDepartKiyose(BusDirection.MusashiKoganeiDepart, "清瀬駅", "清瀬駅", listOf("清瀬駅南口"), "清瀬駅南口"),
    MkDepartHigashiKurume(BusDirection.MusashiKoganeiDepart, "東久留米駅", "東久留米駅", listOf("東久留米駅西口"), "東久留米駅西口"),
    MkDepartHigashiKoganei(BusDirection.MusashiKoganeiDepart, "東小金井駅", "東小金井駅", listOf("東小金井駅"), "東小金井駅"),
    MkDepartMusashiSakai(BusDirection.MusashiKoganeiDepart, "武蔵境駅", "武蔵境駅", listOf("武蔵境駅南口", "武蔵境駅"), "武蔵境駅南口"),
    MkDepartMitaka(BusDirection.MusashiKoganeiDepart, "三鷹駅", "三鷹駅", listOf("三鷹駅"), "三鷹駅"),
    MkDepartShinKoganei(BusDirection.MusashiKoganeiDepart, "新小金井駅", "新小金井駅", listOf("新小金井駅"), "新小金井駅"),
    MkDepartKitaFuchu(BusDirection.MusashiKoganeiDepart, "北府中駅", "北府中駅", listOf("東芝前"), "東芝前"),
    MkDepartFuchu(BusDirection.MusashiKoganeiDepart, "府中駅", "府中駅", listOf("府中駅"), "府中駅"),
    MkDepartHigashiFuchu(BusDirection.MusashiKoganeiDepart, "東府中駅", "東府中駅", listOf("東府中駅"), "東府中駅"),
    MkDepartTamaReien(BusDirection.MusashiKoganeiDepart, "多磨霊園駅", "多磨霊園駅", listOf("多磨霊園駅"), "多磨霊園駅"),
    MkDepartChofu(BusDirection.MusashiKoganeiDepart, "調布駅", "調布駅", listOf("調布駅北口"), "調布駅北口"),
    MkDepartHanedaT2(BusDirection.MusashiKoganeiDepart, "羽田空港T2", "羽田空港第２ターミナル", listOf("羽田空港第２ターミナル"), "羽田空港第２ターミナル"),
    MkDepartHanedaT1(BusDirection.MusashiKoganeiDepart, "羽田空港T1", "羽田空港第１ターミナル", listOf("羽田空港第１ターミナル"), "羽田空港第１ターミナル"),
    MkDepartHanedaT3(BusDirection.MusashiKoganeiDepart, "羽田空港T3", "羽田空港第３ターミナル", listOf("羽田空港第３ターミナル"), "羽田空港第３ターミナル"),
    CfHino(BusDirection.ChuoDoFuchuDepart, "日野", "中央道日野", listOf("中央道日野"), "中央道日野"),
    CfSagamiko(BusDirection.ChuoDoFuchuDepart, "相模湖", "中央道相模湖", listOf("中央道相模湖", "相模湖"), "中央道相模湖"),
    CfUenohara(BusDirection.ChuoDoFuchuDepart, "上野原", "中央道上野原", listOf("中央道上野原", "上野原"), "中央道上野原"),
    CfSaruhashi(BusDirection.ChuoDoFuchuDepart, "猿橋", "中央道猿橋", listOf("中央道猿橋", "猿橋"), "中央道猿橋"),
    CfFujiQHighland(BusDirection.ChuoDoFuchuDepart, "富士急ハイランド", "富士急ハイランド", listOf("富士急ハイランド"), "富士急ハイランド"),
    CfFujisanStation(BusDirection.ChuoDoFuchuDepart, "富士山駅", "富士山駅", listOf("富士山駅"), "富士山駅"),
    CfKawaguchiko(BusDirection.ChuoDoFuchuDepart, "河口湖", "河口湖", listOf("河口湖駅", "河口湖"), "河口湖駅"),
    CfOshinoHakkai(BusDirection.ChuoDoFuchuDepart, "忍野八海", "忍野八海", listOf("忍野八海"), "忍野八海"),
    CfFujisanYamanakako(BusDirection.ChuoDoFuchuDepart, "富士山・山中湖", "富士山・山中湖", listOf("富士山・山中湖"), "富士山・山中湖"),
    CfYamanakakoAsahigaoka(BusDirection.ChuoDoFuchuDepart, "山中湖旭日丘", "山中湖旭日丘", listOf("山中湖旭日丘"), "山中湖旭日丘"),
    CfYamanakakoHirano(BusDirection.ChuoDoFuchuDepart, "山中湖平野", "山中湖平野", listOf("山中湖平野"), "山中湖平野"),
    CfKaiYamato(BusDirection.ChuoDoFuchuDepart, "甲斐大和", "中央道甲斐大和", listOf("中央道甲斐大和", "甲斐大和"), "中央道甲斐大和"),
    CfIsawa(BusDirection.ChuoDoFuchuDepart, "石和", "石和", listOf("石和"), "石和"),
    CfZenkoji(BusDirection.ChuoDoFuchuDepart, "善光寺", "善光寺", listOf("善光寺"), "善光寺"),
    CfKofu(BusDirection.ChuoDoFuchuDepart, "甲府", "甲府駅", listOf("甲府駅前", "甲府駅"), "甲府駅前"),
    CfRyuo(BusDirection.ChuoDoFuchuDepart, "竜王", "竜王", listOf("竜王"), "竜王"),
    CfMinobu(BusDirection.ChuoDoFuchuDepart, "身延", "身延", listOf("身延"), "身延"),
    CfKobuchizawa(BusDirection.ChuoDoFuchuDepart, "小淵沢", "中央道小淵沢", listOf("中央道小淵沢", "小淵沢"), "中央道小淵沢"),
    CfChino(BusDirection.ChuoDoFuchuDepart, "茅野", "茅野", listOf("茅野駅", "茅野"), "茅野"),
    CfKamiSuwa(BusDirection.ChuoDoFuchuDepart, "上諏訪", "上諏訪駅", listOf("上諏訪駅", "上諏訪"), "上諏訪駅"),
    CfShimoSuwa(BusDirection.ChuoDoFuchuDepart, "下諏訪", "下諏訪", listOf("下諏訪"), "下諏訪"),
    CfOkaya(BusDirection.ChuoDoFuchuDepart, "岡谷", "岡谷駅", listOf("岡谷駅", "岡谷市役所前", "岡谷"), "岡谷駅"),
    CfTatsuno(BusDirection.ChuoDoFuchuDepart, "辰野", "辰野", listOf("辰野"), "辰野"),
    CfShiojiri(BusDirection.ChuoDoFuchuDepart, "塩尻", "塩尻", listOf("塩尻"), "塩尻"),
    CfInaBt(BusDirection.ChuoDoFuchuDepart, "伊那BT", "伊那バスターミナル", listOf("伊那BT", "伊那バスターミナル", "伊那バスセンター"), "伊那BT"),
    CfNaraijuku(BusDirection.ChuoDoFuchuDepart, "奈良井宿", "奈良井宿", listOf("奈良井宿", "奈良井"), "奈良井宿"),
    CfKomaganeBt(BusDirection.ChuoDoFuchuDepart, "駒ヶ根", "駒ヶ根", listOf("駒ヶ根市BT", "駒ヶ根"), "駒ヶ根市BT"),
    CfAzuminoHotaka(BusDirection.ChuoDoFuchuDepart, "安曇野穂高", "安曇野穂高", listOf("安曇野穂高"), "安曇野穂高"),
    CfKisoFukushima(BusDirection.ChuoDoFuchuDepart, "木曽福島", "木曽福島駅", listOf("木曽福島駅前", "木曽福島駅", "木曽福島"), "木曽福島駅前"),
    CfShinanoOmachi(BusDirection.ChuoDoFuchuDepart, "信濃大町", "信濃大町駅", listOf("信濃大町駅前", "信濃大町駅", "信濃大町"), "信濃大町駅前"),
    CfIida(BusDirection.ChuoDoFuchuDepart, "飯田", "飯田駅", listOf("飯田駅", "飯田"), "飯田駅"),
    CfHakubaTown(BusDirection.ChuoDoFuchuDepart, "白馬町", "白馬町", listOf("白馬町"), "白馬町"),
    CfHakubaHappoBt(BusDirection.ChuoDoFuchuDepart, "白馬八方BT", "白馬八方バスターミナル", listOf("白馬八方BT", "白馬八方"), "白馬八方BT"),
    CfHirayuOnsen(BusDirection.ChuoDoFuchuDepart, "平湯温泉", "平湯温泉", listOf("平湯温泉"), "平湯温泉"),
    CfTakayamaNohiBc(BusDirection.ChuoDoFuchuDepart, "高山濃飛BC", "高山濃飛バスセンター", listOf("高山濃飛バスセンター", "高山濃飛BC"), "高山濃飛バスセンター"),
    CfNakatsugawa(BusDirection.ChuoDoFuchuDepart, "中津川", "中津川", listOf("中津川駅", "中津川IC", "中津川"), "中津川IC"),
    CfEna(BusDirection.ChuoDoFuchuDepart, "恵那", "恵那", listOf("恵那"), "恵那"),
    CfMizunamiTentoku(BusDirection.ChuoDoFuchuDepart, "瑞浪天徳", "中央道瑞浪天徳", listOf("中央道瑞浪天徳", "瑞浪天徳"), "中央道瑞浪天徳"),
    CfTajimi(BusDirection.ChuoDoFuchuDepart, "多治見", "多治見", listOf("多治見駅", "多治見"), "多治見"),
    CfKani(BusDirection.ChuoDoFuchuDepart, "可児", "可児", listOf("可児駅", "可児"), "可児"),
    CfKachigawa(BusDirection.ChuoDoFuchuDepart, "勝川", "勝川駅", listOf("勝川駅前", "勝川駅", "勝川"), "勝川駅前"),
    CfSakae(BusDirection.ChuoDoFuchuDepart, "栄", "栄", listOf("栄"), "栄"),
    CfMeitetsuBc(BusDirection.ChuoDoFuchuDepart, "名鉄BC", "名鉄バスセンター", listOf("名鉄バスセンター", "名鉄BC"), "名鉄バスセンター"),
    CfKyoto(BusDirection.ChuoDoFuchuDepart, "京都", "京都駅八条口", listOf("京都駅 八条口F3", "京都駅八条口", "京都駅"), "京都駅 八条口F3"),
    CfUmeda(BusDirection.ChuoDoFuchuDepart, "梅田", "大阪駅前東梅田", listOf("大阪駅前(東梅田)", "大阪駅前", "東梅田"), "大阪駅前(東梅田)"),
    CfNamba(BusDirection.ChuoDoFuchuDepart, "なんば", "なんばオーキャット", listOf("なんばOCAT", "なんば"), "なんばOCAT"),
    CfAbenobashi(BusDirection.ChuoDoFuchuDepart, "あべの橋", "あべの橋", listOf("あべの橋"), "あべの橋"),
    CfUsj(BusDirection.ChuoDoFuchuDepart, "USJ", "ユニバーサル・スタジオ・ジャパン", listOf("ユニバーサル・スタジオ・ジャパン", "USJ"), "ユニバーサル・スタジオ・ジャパン"),
    ;

    fun matchesStop(stopName: String): Boolean =
        matchNames.any { match ->
            when {
                stopName.contains(match) -> true
                // 「原」が「上野原」に含まれるような、短いバス停名の誤ヒットを防ぐ
                stopName.length >= 2 && match.contains(stopName) -> true
                else -> false
            }
        }

    fun displayStopLabel(candidates: Collection<String>): String {
        preferName?.let { prefer ->
            if (candidates.any { it.contains(prefer) }) {
                return prefer
            }
        }
        return matchNames.firstOrNull { prefer -> candidates.any { it.contains(prefer) } }
            ?: candidates.firstOrNull()
            ?: filterLabel
    }

    companion object {
        fun forDirection(direction: BusDirection): List<BusViaFilter> =
            entries.filter { it.direction == direction }
    }
}

data class BusViaArrival(
    val at: LocalDateTime,
    val stopLabel: String,
)

/** 一路線上の停留所。着と発が同じ分なら表示側で1つにまとめる。 */
data class BusStop(
    val name: String,
    val arrival: LocalTime? = null,
    val departure: LocalTime? = null,
) {
    val displayName: String
        get() = withoutBusStopLocality(name)
}

private val BusStopLocalitySuffix = Regex("""[（(［\[]([^）)］\]]+)[）)］\]]$""")

/** 市区町村名だけで括るときの表記。〜市を付けないもの。 */
private val BusStopLocalityNames = setOf(
    "東京", "東久留米", "東村山", "清瀬", "国立", "三鷹", "府中", "調布", "立川", "小平", "国分寺", "小金井", "武蔵野", "西東京", "八王子", "町田", "日野", "昭島",
    "福生", "羽村", "青梅", "あきる野", "武蔵村山", "稲城", "狛江", "多摩", "所沢", "新座", "朝霞", "和光", "志木", "入間", "飯能", "狭山", "川越",
)

private fun isBusStopLocality(label: String): Boolean {
    val parts = label.split('・', '･').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) {
        return false
    }
    return parts.all { part ->
        part.last() in "都道府県市区町村" || part in BusStopLocalityNames
    }
}

/** 末尾の（東京都）（〜市）（東久留米）［東京］などを外す。 */
fun withoutBusStopLocality(name: String): String {
    var current = name.trim()
    while (true) {
        val match = BusStopLocalitySuffix.find(current) ?: break
        if (!isBusStopLocality(match.groupValues[1])) {
            break
        }
        current = current.removeRange(match.range).trim()
    }
    return current.ifBlank { name.trim() }
}

/** 停車停留所の取り方。時刻表を読むときに便へ持たせる。 */
sealed class BusStopRef {
    data class Embedded(val stops: List<BusStop>) : BusStopRef()

    /** 西武・立川（transfer-cloud）の便ごとの通過時刻。 */
    data class TransferCloud(
        val operator: String,
        val tripId: String,
        val busstopId: String,
        val courseId: String,
        val sequence: Int,
        val datetime: String,
    ) : BusStopRef()

    /** NAVITIME 公開ダイヤの停車停留所ページ。 */
    data class Navitime(val url: String) : BusStopRef()

    /** 京王バスナビの系統時刻表。 */
    data class Keio(val url: String) : BusStopRef()
}

enum class BusScheduleKind {
    Weekday,
    Saturday,
    Holiday,
}

data class BusTrip(
    val id: String,
    val company: BusCompany,
    val routeName: String,
    val direction: BusDirection,
    /**
     * 行先表示用。通常は「○○」のみ（UI側で「行き」を付与）。
     * すでに「行き」を含む詳細表記の場合はそのまま使う。
     */
    val destinationLabel: String,
    val speechDestinationLabel: String,
    val originStop: String,
    val departureAt: LocalDateTime,
    /** 国分寺方面など、常時着時刻を出す場合 */
    val arrivalAt: LocalDateTime? = null,
    val arrivalStopLabel: String? = null,
    val arrivalNote: String? = null,
    val isNightBus: Boolean = false,
    /** 絞り込み時に使う経由地ごとの着時刻 */
    val viaArrivals: Map<BusViaFilter, BusViaArrival> = emptyMap(),
    /** タップしたときに出す停車停留所。無い便は取得できない */
    val stopRef: BusStopRef? = null,
) {
    val companyRouteLabel: String
        get() = "${company.displayName} $routeName"

    /** 一覧・ダイアログ用の行先文言（循環には「行き」を付けない） */
    val destinationDisplayText: String
        get() = when {
            destinationLabel.contains("行き") || destinationLabel.contains("循環") -> destinationLabel
            else -> "${destinationLabel}行き"
        }

    /** 読み上げ用の行先（循環には「行き」を付けない） */
    val speechDestinationDisplayText: String
        get() = when {
            speechDestinationLabel.contains("行き") ||
                speechDestinationLabel.contains("循環") ||
                destinationLabel.contains("循環") -> speechDestinationLabel
            else -> "${speechDestinationLabel}行き"
        }

    fun matchesVia(filter: BusViaFilter): Boolean = viaArrivals.containsKey(filter)
}

/** バス時刻表の日付切替時刻（この時刻未満は前日扱い）。 */
const val BusDayRolloverHour: Int = 2

fun busScheduleKind(date: LocalDate): BusScheduleKind {
    return when {
        isJapanesePublicHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> BusScheduleKind.Holiday
        date.dayOfWeek == DayOfWeek.SATURDAY -> BusScheduleKind.Saturday
        else -> BusScheduleKind.Weekday
    }
}

/** バス時刻表上の営業日。0〜1時台は前日のダイヤ。 */
fun busServiceDate(now: LocalDateTime): LocalDate {
    val date = now.toLocalDate()
    return if (now.hour < BusDayRolloverHour) date.minusDays(1) else date
}

/**
 * ダイヤ上の時刻を絶対時刻にする。
 * 0〜1時台（表示上の24〜25時）はサービス日の翌日0〜1時。
 */
fun LocalDate.atBusTime(time: LocalTime): LocalDateTime {
    val base = LocalDateTime.of(this, time)
    return if (time.hour < BusDayRolloverHour) base.plusDays(1) else base
}

/** 発車・到着の表示。0〜1時台は 24〜25 時表記。 */
fun formatBusTime(dateTime: LocalDateTime): String {
    val hour = dateTime.hour
    val displayHour = if (hour < BusDayRolloverHour) hour + 24 else hour
    return "$displayHour:${dateTime.minute.toString().padStart(2, '0')}"
}

/** 乗車通知一覧など用（営業日の日付 + 24時表記）。 */
fun formatBusDateTime(dateTime: LocalDateTime): String {
    val serviceDate = busServiceDate(dateTime)
    return "${serviceDate.monthValue}/${serviceDate.dayOfMonth} ${formatBusTime(dateTime)}"
}

/** 系統名の数字を半角にし、西武の＜＞を除く。 */
fun normalizeBusRouteName(raw: String): String {
    val stripped = raw.replace("＜", "").replace("＞", "").replace("<", "").replace(">", "")
    return buildString(stripped.length) {
        for (ch in stripped) {
            append(
                when (ch) {
                    in '０'..'９' -> ('0'.code + (ch.code - '０'.code)).toChar()
                    '－' -> '-'
                    else -> ch
                },
            )
        }
    }
}

/**
 * 乗車通知の読み上げ。
 * 句読点で間を取る。⇒は「から」、行きは「ゆき」。
 * 系統の寺／武／府／境／花は読み替える。
 */
fun speechBusReminderText(
    companyName: String,
    originStop: String,
    routeName: String,
    destination: String,
    minutesBefore: Int,
): String {
    val spokenRoute = speechBusRouteName(routeName)
    return "${companyName}、${originStop}発、${spokenRoute}系統、${destination}の発車まで、あと${minutesBefore}分です"
        .replace("⇒", "から")
        .replace("行き", "ゆき")
}

/** 系統名の読み上げ（寺→じ、武→む、府→ふ、境→さかい、花→はな。地名は触らない）。 */
fun speechBusRouteName(routeName: String): String {
    return when {
        routeName.startsWith("寺") -> "じ" + routeName.removePrefix("寺")
        routeName.startsWith("武") -> "む" + routeName.removePrefix("武")
        routeName.startsWith("府") -> "ふ" + routeName.removePrefix("府")
        routeName.startsWith("境") -> "さかい" + routeName.removePrefix("境")
        routeName.startsWith("花") -> "はな" + routeName.removePrefix("花")
        else -> routeName
    }
}

fun isNightBusLabel(courseName: String, rubies: List<String> = emptyList()): Boolean {
    if ("深夜" in courseName) {
        return true
    }
    return rubies.any { it.contains("深") || it.contains("深夜") }
}
