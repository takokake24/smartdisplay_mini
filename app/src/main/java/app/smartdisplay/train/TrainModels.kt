package app.smartdisplay.train

import androidx.compose.ui.graphics.Color
import app.smartdisplay.R
import app.smartdisplay.bus.BusDayRolloverHour
import app.smartdisplay.jihou.isJapanesePublicHoliday
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private const val YahooKokubunji = "22676"
private const val YahooMitaka = "22986"
private const val YahooKichijoji = "22637"
private const val YahooOgikubo = "22573"
private const val YahooShinjuku = "22741"
private const val YahooShinjukuMetro = "29342"
private const val YahooSeibuShinjuku = "22768"
private const val YahooNakanoSakaue = "22850"
private const val YahooNishiShinjuku = "29085"
private const val YahooTokyo = "22828"
private const val YahooNishiKokubunji = "22872"
private const val YahooTachikawa = "22799"
private const val ChuoRapidTokyoHeading = "JR中央快速線 中野・新宿・東京方面"
private const val ChuoRapidWestHeading = "JR中央快速線 国分寺・立川・高尾・青梅方面"
private const val ChuoLocalChibaHeading =
    "JR中央緩行線 中野・新宿・西船橋・千葉・<T>浦安・<TR>東葉勝田台方面"
private const val ChuoLocalMitakaHeading = "JR中央緩行線 三鷹方面"

enum class TrainStation(val label: String) {
    Kokubunji("国分寺"),
    Mitaka("三鷹"),
    Kichijoji("吉祥寺"),
    Ogikubo("荻窪"),
    NakanoSakaue("中野坂上"),
    NishiShinjuku("西新宿"),
    Shinjuku("新宿"),
    Tokyo("東京"),
    NishiKokubunji("西国分寺"),
    Tachikawa("立川"),
}

enum class TrainDirection(
    val station: TrainStation,
    val tabLabel: String,
    val heading: String,
    val yahooStationId: String,
    val yahooGroupId: String,
    val iconResId: Int,
    /** 中央線快速。各駅停車は普通と表示する。 */
    val isChuo: Boolean = false,
    /** 同じ時刻表を分ける。joban は常磐線、utsu は宇都宮線・高崎線。 */
    val board: String = "",
) {
    ChuoTokyo(station = TrainStation.Kokubunji, tabLabel = "中央線 東京方面", heading = "JR中央線 三鷹・新宿・東京方面", yahooStationId = YahooKokubunji, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    ChuoTachikawa(station = TrainStation.Kokubunji, tabLabel = "中央線 立川方面", heading = "JR中央線 立川・高尾・青梅方面", yahooStationId = YahooKokubunji, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    SeibuKokubunji(station = TrainStation.Kokubunji, tabLabel = "国分寺線", heading = "西武国分寺線 小川・東村山方面", yahooStationId = YahooKokubunji, yahooGroupId = "2921", iconResId = R.drawable.ic_line_seibu_kokubunji),
    SeibuTamako(station = TrainStation.Kokubunji, tabLabel = "多摩湖線", heading = "西武多摩湖線 萩山・多摩湖方面", yahooStationId = YahooKokubunji, yahooGroupId = "2911", iconResId = R.drawable.ic_line_seibu_tamako),
    MitakaRapidTokyo(station = TrainStation.Mitaka, tabLabel = "中央快速線 東京方面", heading = ChuoRapidTokyoHeading, yahooStationId = YahooMitaka, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    MitakaRapidWest(station = TrainStation.Mitaka, tabLabel = "中央快速線 立川方面", heading = ChuoRapidWestHeading, yahooStationId = YahooMitaka, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    MitakaLocalChiba(station = TrainStation.Mitaka, tabLabel = "中央緩行線 千葉方面", heading = ChuoLocalChibaHeading, yahooStationId = YahooMitaka, yahooGroupId = "1100", iconResId = R.drawable.ic_line_jr_jb),
    KichijojiRapidTokyo(station = TrainStation.Kichijoji, tabLabel = "中央快速線 東京方面", heading = ChuoRapidTokyoHeading, yahooStationId = YahooKichijoji, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    KichijojiRapidWest(station = TrainStation.Kichijoji, tabLabel = "中央快速線 立川方面", heading = ChuoRapidWestHeading, yahooStationId = YahooKichijoji, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    KichijojiLocalChiba(station = TrainStation.Kichijoji, tabLabel = "中央緩行線 千葉方面", heading = ChuoLocalChibaHeading, yahooStationId = YahooKichijoji, yahooGroupId = "1100", iconResId = R.drawable.ic_line_jr_jb),
    KichijojiLocalMitaka(station = TrainStation.Kichijoji, tabLabel = "中央緩行線 三鷹方面", heading = ChuoLocalMitakaHeading, yahooStationId = YahooKichijoji, yahooGroupId = "1101", iconResId = R.drawable.ic_line_jr_jb),
    KichijojiInokashira(station = TrainStation.Kichijoji, tabLabel = "井の頭線 渋谷方面", heading = "京王井の頭線 久我山・永福町・明大前・下北沢・渋谷方面", yahooStationId = YahooKichijoji, yahooGroupId = "3080", iconResId = R.drawable.ic_line_keio_in),
    OgikuboRapidTokyo(station = TrainStation.Ogikubo, tabLabel = "中央快速線 東京方面", heading = ChuoRapidTokyoHeading, yahooStationId = YahooOgikubo, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    OgikuboRapidWest(station = TrainStation.Ogikubo, tabLabel = "中央快速線 立川方面", heading = ChuoRapidWestHeading, yahooStationId = YahooOgikubo, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    OgikuboLocalChiba(station = TrainStation.Ogikubo, tabLabel = "中央緩行線 千葉方面", heading = ChuoLocalChibaHeading, yahooStationId = YahooOgikubo, yahooGroupId = "1100", iconResId = R.drawable.ic_line_jr_jb),
    OgikuboLocalMitaka(station = TrainStation.Ogikubo, tabLabel = "中央緩行線 三鷹方面", heading = ChuoLocalMitakaHeading, yahooStationId = YahooOgikubo, yahooGroupId = "1101", iconResId = R.drawable.ic_line_jr_jb),
    OgikuboMarunouchi(station = TrainStation.Ogikubo, tabLabel = "丸ノ内線 池袋方面", heading = "東京メトロ丸ノ内線 新宿・四ツ谷・霞ケ関・東京・池袋・<Mb>方南町方面", yahooStationId = YahooOgikubo, yahooGroupId = "3280", iconResId = R.drawable.ic_line_marunouchi),
    ShinjukuRapidTokyo(station = TrainStation.Shinjuku, tabLabel = "中央快速線 東京方面", heading = "JR中央快速線 四ツ谷・東京方面", yahooStationId = YahooShinjuku, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    ShinjukuRapidWest(station = TrainStation.Shinjuku, tabLabel = "中央快速線 立川方面", heading = "JR中央快速線 中野・三鷹・国分寺・立川・高尾・青梅方面", yahooStationId = YahooShinjuku, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    ShinjukuLocalChiba(station = TrainStation.Shinjuku, tabLabel = "中央緩行線 千葉方面", heading = "JR中央緩行線 四ツ谷・秋葉原・西船橋・千葉方面", yahooStationId = YahooShinjuku, yahooGroupId = "1100", iconResId = R.drawable.ic_line_jr_jb),
    ShinjukuLocalMitaka(station = TrainStation.Shinjuku, tabLabel = "中央緩行線 三鷹方面", heading = "JR中央緩行線 中野・三鷹方面", yahooStationId = YahooShinjuku, yahooGroupId = "1101", iconResId = R.drawable.ic_line_jr_jb),
    ShinjukuYamanoteShibuya(station = TrainStation.Shinjuku, tabLabel = "山手線 渋谷・品川方面", heading = "JR山手線 内回り：渋谷・品川方面", yahooStationId = YahooShinjuku, yahooGroupId = "7170", iconResId = R.drawable.ic_line_jr_jy),
    ShinjukuYamanoteIkebukuro(station = TrainStation.Shinjuku, tabLabel = "山手線 池袋・上野方面", heading = "JR山手線 外回り：池袋・上野方面", yahooStationId = YahooShinjuku, yahooGroupId = "7171", iconResId = R.drawable.ic_line_jr_jy),
    ShinjukuSaikyoOmiya(station = TrainStation.Shinjuku, tabLabel = "埼京線 大宮方面", heading = "JR埼京線 池袋・赤羽・武蔵浦和・大宮・川越方面", yahooStationId = YahooShinjuku, yahooGroupId = "1061", iconResId = R.drawable.ic_line_jr_ja),
    ShinjukuSaikyoOsaki(station = TrainStation.Shinjuku, tabLabel = "埼京線 大崎方面", heading = "JR埼京線 渋谷・大崎・武蔵小杉・<R>りんかい線・<SO>相鉄線方面", yahooStationId = YahooShinjuku, yahooGroupId = "1060", iconResId = R.drawable.ic_line_jr_ja),
    ShinjukuShonanOmiya(station = TrainStation.Shinjuku, tabLabel = "湘南新宿ライン 大宮方面", heading = "JR湘南新宿ライン 池袋・赤羽・浦和・大宮・宇都宮・高崎方面", yahooStationId = YahooShinjuku, yahooGroupId = "1051", iconResId = R.drawable.ic_line_jr_js),
    ShinjukuShonanYokohama(station = TrainStation.Shinjuku, tabLabel = "湘南新宿ライン 横浜方面", heading = "JR湘南新宿ライン 渋谷・大崎・武蔵小杉・横浜・<JO>逗子方面", yahooStationId = YahooShinjuku, yahooGroupId = "1050", iconResId = R.drawable.ic_line_jr_js),
    ShinjukuMarunouchiIkebukuro(station = TrainStation.Shinjuku, tabLabel = "丸ノ内線 池袋方面", heading = "東京メトロ丸ノ内線 四ツ谷・霞ケ関・東京・池袋方面", yahooStationId = YahooShinjukuMetro, yahooGroupId = "3280", iconResId = R.drawable.ic_line_marunouchi),
    ShinjukuMarunouchiOgikubo(station = TrainStation.Shinjuku, tabLabel = "丸ノ内線 荻窪方面", heading = "東京メトロ丸ノ内線 中野坂上・荻窪・<Mb>方南町方面", yahooStationId = YahooShinjukuMetro, yahooGroupId = "3281", iconResId = R.drawable.ic_line_marunouchi),
    ShinjukuToeiShinjuku(station = TrainStation.Shinjuku, tabLabel = "都営新宿線 本八幡方面", heading = "都営地下鉄新宿線 市ヶ谷・神保町・馬喰横山・森下・本八幡方面", yahooStationId = YahooShinjuku, yahooGroupId = "3401", iconResId = R.drawable.ic_line_toei_s),
    ShinjukuOedoDaimon(station = TrainStation.Shinjuku, tabLabel = "都営大江戸線 大門方面", heading = "都営地下鉄大江戸線 六本木・大門方面", yahooStationId = YahooShinjuku, yahooGroupId = "7210", iconResId = R.drawable.ic_line_toei_e),
    ShinjukuOedoHikarigaoka(station = TrainStation.Shinjuku, tabLabel = "都営大江戸線 光が丘方面", heading = "都営地下鉄大江戸線 都庁前・中野坂上・練馬・光が丘方面", yahooStationId = YahooShinjuku, yahooGroupId = "7211", iconResId = R.drawable.ic_line_toei_e),
    ShinjukuKeio(station = TrainStation.Shinjuku, tabLabel = "京王本線 京王八王子方面", heading = "京王本線 笹塚・明大前・調布・京王八王子・高尾山口・橋本方面", yahooStationId = YahooShinjuku, yahooGroupId = "3021", iconResId = R.drawable.ic_line_keio_ko),
    ShinjukuKeioNew(station = TrainStation.Shinjuku, tabLabel = "京王新線 京王八王子方面", heading = "京王新線 笹塚・明大前・調布・京王八王子・高尾山口・橋本方面", yahooStationId = YahooShinjuku, yahooGroupId = "3031", iconResId = R.drawable.ic_line_keio_ko),
    ShinjukuOdakyu(station = TrainStation.Shinjuku, tabLabel = "小田急線 小田原方面", heading = "小田急線 下北沢・登戸・小田原・<OT>唐木田・<OE>片瀬江ノ島・<OH(hakone)>箱根湯本方面", yahooStationId = YahooShinjuku, yahooGroupId = "3091", iconResId = R.drawable.ic_line_odakyu_oh),
    ShinjukuSeibu(station = TrainStation.Shinjuku, tabLabel = "新宿線 本川越方面", heading = "西武新宿線 上石神井・小平・所沢・本川越・拝島方面", yahooStationId = YahooSeibuShinjuku, yahooGroupId = "2881", iconResId = R.drawable.ic_line_seibu_ss),
    NakanoSakaueMarunouchiIkebukuro(station = TrainStation.NakanoSakaue, tabLabel = "丸ノ内線 池袋方面", heading = "東京メトロ丸ノ内線 新宿・四ツ谷・霞ケ関・東京・池袋方面", yahooStationId = YahooNakanoSakaue, yahooGroupId = "3280", iconResId = R.drawable.ic_line_marunouchi),
    NakanoSakaueMarunouchiOgikubo(station = TrainStation.NakanoSakaue, tabLabel = "丸ノ内線 荻窪方面", heading = "東京メトロ丸ノ内線 荻窪方面", yahooStationId = YahooNakanoSakaue, yahooGroupId = "3281", iconResId = R.drawable.ic_line_marunouchi),
    NakanoSakaueMarunouchiHonancho(station = TrainStation.NakanoSakaue, tabLabel = "丸ノ内線 方南町方面", heading = "東京メトロ丸ノ内線 中野富士見町・方南町方面", yahooStationId = YahooNakanoSakaue, yahooGroupId = "3291", iconResId = R.drawable.ic_line_metro_mb),
    NakanoSakaueOedoDaimon(station = TrainStation.NakanoSakaue, tabLabel = "都営大江戸線 大門方面", heading = "都営地下鉄大江戸線 都庁前・新宿・六本木・大門方面", yahooStationId = YahooNakanoSakaue, yahooGroupId = "7210", iconResId = R.drawable.ic_line_toei_e),
    NakanoSakaueOedoHikarigaoka(station = TrainStation.NakanoSakaue, tabLabel = "都営大江戸線 光が丘方面", heading = "都営地下鉄大江戸線 練馬・光が丘方面", yahooStationId = YahooNakanoSakaue, yahooGroupId = "7211", iconResId = R.drawable.ic_line_toei_e),
    NishiShinjukuMarunouchiIkebukuro(station = TrainStation.NishiShinjuku, tabLabel = "丸ノ内線 池袋方面", heading = "東京メトロ丸ノ内線 新宿・四ツ谷・霞ケ関・東京・池袋方面", yahooStationId = YahooNishiShinjuku, yahooGroupId = "3280", iconResId = R.drawable.ic_line_marunouchi),
    NishiShinjukuMarunouchiOgikubo(station = TrainStation.NishiShinjuku, tabLabel = "丸ノ内線 荻窪方面", heading = "東京メトロ丸ノ内線 中野坂上・荻窪方面", yahooStationId = YahooNishiShinjuku, yahooGroupId = "3281", iconResId = R.drawable.ic_line_marunouchi),
    TokyoChuo(station = TrainStation.Tokyo, tabLabel = "中央線", heading = "JR中央線 御茶ノ水・四ツ谷・新宿・立川・高尾・青梅方面", yahooStationId = YahooTokyo, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    TokyoTokaido(station = TrainStation.Tokyo, tabLabel = "東海道線", heading = "JR東海道線 品川・横浜・小田原・熱海方面", yahooStationId = YahooTokyo, yahooGroupId = "1171", iconResId = R.drawable.ic_line_jr_jt),
    TokyoUtsunomiyaTakasaki(station = TrainStation.Tokyo, tabLabel = "宇都宮線・高崎線", heading = "JR宇都宮線・高崎線 上野・赤羽・浦和・大宮・宇都宮・高崎方面", yahooStationId = YahooTokyo, yahooGroupId = "7910", iconResId = R.drawable.ic_line_jr_ju, board = "utsu"),
    TokyoJoban(station = TrainStation.Tokyo, tabLabel = "常磐線", heading = "JR常磐線 上野・柏・取手方面", yahooStationId = YahooTokyo, yahooGroupId = "7910", iconResId = R.drawable.ic_line_jr_jj, board = "joban"),
    TokyoKeihinOfuna(station = TrainStation.Tokyo, tabLabel = "京浜東北線 大船方面", heading = "JR京浜東北線 品川・蒲田・横浜・大船方面", yahooStationId = YahooTokyo, yahooGroupId = "1151", iconResId = R.drawable.ic_line_jr_jk),
    TokyoKeihinOmiya(station = TrainStation.Tokyo, tabLabel = "京浜東北線 大宮方面", heading = "JR京浜東北線 上野・赤羽・浦和・大宮方面", yahooStationId = YahooTokyo, yahooGroupId = "1150", iconResId = R.drawable.ic_line_jr_jk),
    TokyoYamanoteShibuya(station = TrainStation.Tokyo, tabLabel = "山手線 品川・渋谷方面", heading = "JR山手線 外回り：品川・渋谷方面", yahooStationId = YahooTokyo, yahooGroupId = "7181", iconResId = R.drawable.ic_line_jr_jy),
    TokyoYamanoteIkebukuro(station = TrainStation.Tokyo, tabLabel = "山手線 上野・池袋方面", heading = "JR山手線 内回り：上野・池袋方面", yahooStationId = YahooTokyo, yahooGroupId = "7180", iconResId = R.drawable.ic_line_jr_jy),
    TokyoYokosuka(station = TrainStation.Tokyo, tabLabel = "横須賀線", heading = "JR横須賀線 品川・横浜・大船・逗子・久里浜方面", yahooStationId = YahooTokyo, yahooGroupId = "1161", iconResId = R.drawable.ic_line_jr_jo),
    TokyoSobu(station = TrainStation.Tokyo, tabLabel = "総武線", heading = "JR総武線 市川・千葉・成東・成田空港・上総一ノ宮・君津方面", yahooStationId = YahooTokyo, yahooGroupId = "941", iconResId = R.drawable.ic_line_jr_jo),
    TokyoKeiyo(station = TrainStation.Tokyo, tabLabel = "京葉線", heading = "JR京葉線 新木場・海浜幕張・蘇我・上総一ノ宮・君津・<JM>府中本町方面", yahooStationId = YahooTokyo, yahooGroupId = "951", iconResId = R.drawable.ic_line_jr_je),
    TokyoTohokuShinkansen(station = TrainStation.Tokyo, tabLabel = "東北・北海道新幹線", heading = "東北・北海道新幹線 宇都宮・郡山・福島・仙台・盛岡・新青森・<新幹線北>新函館北斗方面", yahooStationId = YahooTokyo, yahooGroupId = "231", iconResId = R.drawable.ic_line_shinkansen_east),
    TokyoYamagataShinkansen(station = TrainStation.Tokyo, tabLabel = "山形新幹線", heading = "山形新幹線 福島・山形・新庄方面", yahooStationId = YahooTokyo, yahooGroupId = "271", iconResId = R.drawable.ic_line_shinkansen_east),
    TokyoAkitaShinkansen(station = TrainStation.Tokyo, tabLabel = "秋田新幹線", heading = "秋田新幹線 角館・大曲・秋田方面", yahooStationId = YahooTokyo, yahooGroupId = "281", iconResId = R.drawable.ic_line_shinkansen_east),
    TokyoJoetsuShinkansen(station = TrainStation.Tokyo, tabLabel = "上越新幹線", heading = "上越新幹線 高崎・越後湯沢・新潟方面", yahooStationId = YahooTokyo, yahooGroupId = "241", iconResId = R.drawable.ic_line_shinkansen_east),
    TokyoHokurikuShinkansen(station = TrainStation.Tokyo, tabLabel = "北陸新幹線", heading = "北陸新幹線 軽井沢・長野・<新幹線西>金沢・敦賀方面", yahooStationId = YahooTokyo, yahooGroupId = "261", iconResId = R.drawable.ic_line_shinkansen_east),
    TokyoTokaidoShinkansen(station = TrainStation.Tokyo, tabLabel = "東海道新幹線", heading = "東海道新幹線 新横浜・静岡・名古屋・新大阪・<新幹線西>岡山・博多方面", yahooStationId = YahooTokyo, yahooGroupId = "1341", iconResId = R.drawable.ic_line_shinkansen_tokaido),
    TokyoMarunouchiOgikubo(station = TrainStation.Tokyo, tabLabel = "丸ノ内線 荻窪方面", heading = "東京メトロ丸ノ内線 銀座・四ツ谷・新宿・荻窪・<Mb>方南町方面", yahooStationId = YahooTokyo, yahooGroupId = "3281", iconResId = R.drawable.ic_line_marunouchi),
    TokyoMarunouchiIkebukuro(station = TrainStation.Tokyo, tabLabel = "丸ノ内線 池袋方面", heading = "東京メトロ丸ノ内線 大手町・御茶ノ水・後楽園・池袋方面", yahooStationId = YahooTokyo, yahooGroupId = "3280", iconResId = R.drawable.ic_line_marunouchi),
    NishiKokubunjiChuoTokyo(station = TrainStation.NishiKokubunji, tabLabel = "中央線 東京方面", heading = "JR中央線 国分寺・三鷹・中野・新宿・東京方面", yahooStationId = YahooNishiKokubunji, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    NishiKokubunjiChuoTachikawa(station = TrainStation.NishiKokubunji, tabLabel = "中央線 立川方面", heading = "JR中央線 立川・高尾・青梅方面", yahooStationId = YahooNishiKokubunji, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    NishiKokubunjiMusashinoEast(station = TrainStation.NishiKokubunji, tabLabel = "武蔵野線 西船橋方面", heading = "JR武蔵野線 東所沢・武蔵浦和・南越谷・西船橋・<JE>東京・海浜幕張方面", yahooStationId = YahooNishiKokubunji, yahooGroupId = "981", iconResId = R.drawable.ic_line_jr_jm),
    NishiKokubunjiMusashinoWest(station = TrainStation.NishiKokubunji, tabLabel = "武蔵野線 府中本町方面", heading = "JR武蔵野線 府中本町方面", yahooStationId = YahooNishiKokubunji, yahooGroupId = "980", iconResId = R.drawable.ic_line_jr_jm),
    TachikawaChuoTokyo(station = TrainStation.Tachikawa, tabLabel = "中央線 東京方面", heading = "JR中央線 国分寺・三鷹・中野・新宿・東京方面", yahooStationId = YahooTachikawa, yahooGroupId = "1090", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    TachikawaChuoTakao(station = TrainStation.Tachikawa, tabLabel = "中央線 高尾方面", heading = "JR中央線 豊田・八王子・高尾・大月方面", yahooStationId = YahooTachikawa, yahooGroupId = "1091", iconResId = R.drawable.ic_line_jr_jc, isChuo = true),
    TachikawaOme(station = TrainStation.Tachikawa, tabLabel = "青梅線", heading = "JR青梅線 拝島・青梅・奥多摩・武蔵五日市方面", yahooStationId = YahooTachikawa, yahooGroupId = "1111", iconResId = R.drawable.ic_line_jr_jc),
    TachikawaNambu(station = TrainStation.Tachikawa, tabLabel = "南武線", heading = "JR南武線 府中本町・登戸・武蔵小杉・川崎方面", yahooStationId = YahooTachikawa, yahooGroupId = "1210", iconResId = R.drawable.ic_line_jr_jn),
    ;

    /** 同じ駅でこの路線が1方向だけなら方面を外した名前。 */
    fun chipLabel(): String {
        val line = lineName()
        val alone = entries.none { it != this && it.station == station && it.lineName() == line }
        return if (alone) line else tabLabel
    }

    private fun lineName(): String {
        val space = tabLabel.lastIndexOf(' ')
        if (space < 0) {
            return tabLabel
        }
        val tail = tabLabel.substring(space + 1)
        return if (tail.endsWith("方面")) tabLabel.substring(0, space) else tabLabel
    }

    /** 三鷹・吉祥寺・荻窪の中央緩行線千葉方面。中野から東西線へ直通する。 */
    val isChuoLocalEast: Boolean
        get() = yahooGroupId == "1100" &&
            station in setOf(TrainStation.Mitaka, TrainStation.Kichijoji, TrainStation.Ogikubo)
}

/** 固定テキスト内の `<T>` などをナンバリング画像に置き換える。 */
internal enum class TrainLineMark(val token: String, val iconResId: Int) {
    Tozai("T", R.drawable.ic_line_metro_t),
    ToyoRapid("TR", R.drawable.ic_line_toyo_tr),
    Rinkai("R", R.drawable.ic_line_rinkai_r),
    Sotetsu("SO", R.drawable.ic_line_sotetsu_so),
    Yokosuka("JO", R.drawable.ic_line_jr_jo),
    MarunouchiBranch("Mb", R.drawable.ic_line_metro_mb),
    OdakyuTama("OT", R.drawable.ic_line_odakyu_ot),
    OdakyuEnoshima("OE", R.drawable.ic_line_odakyu_oe),
    Hakone("OH(hakone)", R.drawable.ic_line_odakyu_hakone),
    SeibuTamako("ST", R.drawable.ic_line_seibu_tamako),
    Musashino("JM", R.drawable.ic_line_jr_jm),
    Keiyo("JE", R.drawable.ic_line_jr_je),
    ShinkansenNorth("新幹線北", R.drawable.ic_line_shinkansen_north),
    ShinkansenWest("新幹線西", R.drawable.ic_line_shinkansen_west),
    ;

    companion object {
        fun fromToken(token: String): TrainLineMark? = entries.find { it.token == token }
    }
}

internal sealed interface TrainHeadingPart {
    data class Text(val value: String) : TrainHeadingPart
    data class Mark(val iconResId: Int) : TrainHeadingPart
}

private val HeadingToken = Regex("""<([^>]+)>""")

internal fun trainHeadingParts(heading: String): List<TrainHeadingPart> {
    val parts = mutableListOf<TrainHeadingPart>()
    var cursor = 0
    for (match in HeadingToken.findAll(heading)) {
        if (match.range.first > cursor) {
            parts += TrainHeadingPart.Text(heading.substring(cursor, match.range.first))
        }
        val mark = TrainLineMark.fromToken(match.groupValues[1])
        if (mark != null) {
            parts += TrainHeadingPart.Mark(mark.iconResId)
        } else {
            parts += TrainHeadingPart.Text(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < heading.length) {
        parts += TrainHeadingPart.Text(heading.substring(cursor))
    }
    return parts
}

/** Yahoo時刻表の曜日種別。祝日と日曜は休日ダイヤ。 */
internal enum class TrainScheduleKind(val yahooKind: Int) {
    Weekday(1),
    Saturday(2),
    Holiday(4),
}

internal fun trainScheduleKind(date: LocalDate): TrainScheduleKind {
    return when {
        isJapanesePublicHoliday(date) || date.dayOfWeek == DayOfWeek.SUNDAY -> TrainScheduleKind.Holiday
        date.dayOfWeek == DayOfWeek.SATURDAY -> TrainScheduleKind.Saturday
        else -> TrainScheduleKind.Weekday
    }
}

data class TrainTrip(
    val id: String,
    val departureAt: LocalDateTime,
    val typeLabel: String,
    val trainName: String,
    val destination: String,
    val accent: Color,
    val trainId: String,
    val timetableHour: Int,
    val timetableMinute: Int,
    /** 他路線へ直通するときの補足。空なら出さない。 */
    val note: String = "",
    /** この駅が始発。 */
    val originatesHere: Boolean = false,
)

data class TrainStop(
    val name: String,
    val arrival: LocalTime?,
    val departure: LocalTime?,
    val isOrigin: Boolean,
)

data class TrainRoute(
    val stops: List<TrainStop>,
    /** 停車駅を見て分かる種別変更。一覧には出さない。 */
    val detailNote: String = "",
)

data class TrainTimetableDay(
    val date: LocalDate,
    val direction: TrainDirection,
    val trips: List<TrainTrip>,
)

private val TrainTypeOrder = listOf(
    "普通", "各駅停車", "準急", "区間急行", "急行", "快速", "快速急行", "通勤快速", "通勤特快", "中央特快", "青梅特快", "特別快速", "ライナー", "特急", "寝台特急",
    "はやぶさ", "こまち", "やまびこ", "つばさ", "なすの", "とき", "たにがわ", "かがやき", "はくたか", "あさま", "のぞみ", "ひかり", "こだま", "臨時",
)

private val DestinationOrder = listOf(
    "武蔵小金井", "三鷹", "中野", "新宿", "東京", "立川", "豊田", "八王子", "高尾", "大月", "河口湖", "青梅", "小川", "東村山", "萩山", "多摩湖",
)

fun orderedTrainTypes(labels: Iterable<String>): List<String> {
    val present = labels.toSet()
    return TrainTypeOrder.filter { it in present } +
        present.filter { it !in TrainTypeOrder }.sorted()
}

fun orderedDestinations(names: Iterable<String>): List<String> {
    val present = names.distinct()
    val known = DestinationOrder.filter { it in present }
    return known + present.filter { it !in DestinationOrder }
}

/** 種別ラベル。臨時は列車名が違っても同じラベルにする。中央線の各駅停車は普通。 */
internal fun trainTypeLabel(
    kindName: String,
    trainName: String,
    direction: TrainDirection,
): String {
    if ("臨時" in kindName || "臨時" in trainName) {
        return "臨時"
    }
    val name = kindName.ifBlank { trainName }
    return when {
        name == "通勤特別快速" -> "通勤特快"
        name == "各駅停車" && direction.isChuo -> "普通"
        else -> name.ifBlank { "列車" }
    }
}

/** 種別そのものではない列車名。種別の先頭繰り返しは外す。 */
internal fun trainDisplayName(kindName: String, trainName: String): String {
    val raw = trainName.trim()
    val kind = kindName.trim()
    if (raw.isEmpty() || raw == kind) {
        return ""
    }
    val stripped = when {
        kind.isNotEmpty() && raw.startsWith(kind) -> raw.removePrefix(kind).trim()
        else -> raw
    }
    return stripped
}

fun formatTrainStopClock(time: LocalTime): String {
    val hour = if (time.hour < BusDayRolloverHour) time.hour + 24 else time.hour
    return "$hour:${time.minute.toString().padStart(2, '0')}"
}

/** 始発は発、終着は着。同じ分なら1つだけ出す。 */
fun trainStopTimeLabel(arrival: LocalTime?, departure: LocalTime?): String {
    val arrivalText = arrival?.let(::formatTrainStopClock)
    val departureText = departure?.let(::formatTrainStopClock)
    return when {
        arrivalText == null && departureText == null -> ""
        arrivalText == null -> "${departureText}発"
        departureText == null -> "${arrivalText}着"
        arrivalText == departureText -> arrivalText
        else -> "${arrivalText}着\n${departureText}発"
    }
}

internal fun trainDestinationLabel(name: String): String {
    val stripped = name.replace(Regex("""[（(][^）)]*[）)]"""), "").trim()
    return stripped.ifBlank { name.ifBlank { "不明" } }
}

private val TozaiRapidOrder = listOf("東陽町", "南砂町", "西葛西", "葛西", "浦安", "南行徳", "行徳", "妙典", "原木中山", "西船橋")

/** 東陽町から先を、途中の駅を飛ばして走る。 */
internal fun isTozaiInnerRapid(stops: List<TrainStop>): Boolean {
    val present = stops.map { trainDestinationLabel(it.name) }.toSet()
    val passed = TozaiRapidOrder.filter { it in present }
    val toyomachi = TozaiRapidOrder.indexOf("東陽町")
    val last = passed.maxOfOrNull { TozaiRapidOrder.indexOf(it) } ?: return false
    if ("東陽町" !in present || last <= toyomachi) {
        return false
    }
    val between = TozaiRapidOrder.subList(toyomachi + 1, last)
    return between.any { it !in present }
}

/** 環状運転は終点がないので「行き」を付けない。 */
fun trainShowsBoundSuffix(destination: String): Boolean {
    return !destination.endsWith("行き") && "環状" !in destination
}

fun trainTypeColor(label: String): Color {
    return when (label) {
        "普通", "各駅停車" -> Color(0xFF546E7A)
        "準急" -> Color(0xFF00A651)
        "区間急行" -> Color(0xFFC49000)
        "急行" -> Color(0xFFE53935)
        "快速" -> Color(0xFFE8770A)
        "快速急行" -> Color(0xFFFF6D00)
        "通勤快速" -> Color(0xFF00838F)
        "通勤特快" -> Color(0xFFC62828)
        "中央特快" -> Color(0xFF3949AB)
        "青梅特快" -> Color(0xFF2E7D32)
        "特別快速" -> Color(0xFFAD1457)
        "ライナー" -> Color(0xFF1A237E)
        "特急" -> Color(0xFF6A1B9A)
        "寝台特急" -> Color(0xFF004D40)
        "はやぶさ" -> Color(0xFF1B5E20)
        "やまびこ" -> Color(0xFF1565C0)
        "なすの" -> Color(0xFF006064)
        "つばさ" -> Color(0xFFC2185B)
        "こまち" -> Color(0xFF880E4F)
        "とき" -> Color(0xFFBF360C)
        "たにがわ" -> Color(0xFF827717)
        "かがやき" -> Color(0xFF8D6E00)
        "はくたか" -> Color(0xFF0D47A1)
        "あさま" -> Color(0xFF4E342E)
        "のぞみ" -> Color(0xFFB8860B)
        "ひかり" -> Color(0xFF1565C0)
        "こだま" -> Color(0xFF33691E)
        "臨時" -> Color(0xFF6D4C41)
        else -> Color(0xFF455A64)
    }
}

/**
 * 時刻表の「但し、○を除く」にその日付が含まれるときは運転しない。
 * 読み取れない注記は、その列車を残す。
 *
 * 例: 「10月4日〜12月6・26〜31日」は 10/4〜12/6 と 12/26〜12/31。
 */
internal fun trainOperatesOn(driveComment: String, date: LocalDate): Boolean {
    val ranges = excludedDateRanges(driveComment, date.year) ?: return true
    return ranges.none { date in it }
}

private fun excludedDateRanges(driveComment: String, year: Int): List<ClosedRange<LocalDate>>? {
    val marker = when {
        "但し、" in driveComment -> "但し、"
        "但し" in driveComment -> "但し"
        else -> return null
    }
    if ("を除く" !in driveComment) {
        return null
    }
    val body = normalizeDigits(driveComment.substringAfter(marker).substringBefore("を除く")).trim()
    val tokens = body.split('・', '･').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return null
    }
    val pattern = Regex("""^(?:(\d{1,2})月)?(\d{1,2})日?(?:[〜～~-](?:(\d{1,2})月)?(\d{1,2})日?)?$""")
    var carriedMonth: Int? = null
    val ranges = mutableListOf<ClosedRange<LocalDate>>()
    for (token in tokens) {
        val match = pattern.matchEntire(token) ?: return null
        val startMonth = match.groupValues[1].toIntOrNull() ?: carriedMonth ?: return null
        val startDay = match.groupValues[2].toIntOrNull() ?: return null
        val hasRange = match.groupValues[4].isNotEmpty()
        val endMonth = if (hasRange) {
            match.groupValues[3].toIntOrNull() ?: startMonth
        } else {
            startMonth
        }
        val endDay = if (hasRange) {
            match.groupValues[4].toIntOrNull() ?: return null
        } else {
            startDay
        }
        val start = dateOrNull(year, startMonth, startDay) ?: return null
        var end = dateOrNull(year, endMonth, endDay) ?: return null
        if (end.isBefore(start)) {
            end = end.plusYears(1)
        }
        ranges += start..end
        carriedMonth = endMonth
    }
    return ranges
}

private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? {
    return try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }
}

private fun normalizeDigits(text: String): String {
    return buildString(text.length) {
        for (ch in text) {
            append(
                when (ch) {
                    in '０'..'９' -> ('0'.code + (ch.code - '０'.code)).toChar()
                    else -> ch
                },
            )
        }
    }
}

internal val TrainOriginFilters = listOf("当駅始発", "当駅始発以外")

internal val TrainOriginAccent = Color(0xFFE53935)

internal fun trainOriginFilterKey(originatesHere: Boolean): String {
    return if (originatesHere) "当駅始発" else "当駅始発以外"
}

/** null は「すべて」。空セットは何も選んでいない。 */
fun toggleTrainFilter(current: Set<String>?, item: String, all: List<String>): Set<String>? {
    if (all.isEmpty()) {
        return current
    }
    val active = (current ?: all.toSet()).toMutableSet()
    if (!active.add(item)) {
        active.remove(item)
    }
    return when {
        active.isEmpty() -> emptySet()
        active.containsAll(all) -> null
        else -> active
    }
}

/** 全部選ばれているときは解除し、それ以外は全部選ぶ。 */
fun toggleAllTrainFilter(current: Set<String>?): Set<String>? {
    return if (current == null) emptySet() else null
}

fun trainFilterSummary(
    typeFilter: Set<String>?,
    destinationFilter: Set<String>?,
    originFilter: Set<String>?,
    types: List<String>,
    destinations: List<String>,
): String {
    val typeText = trainFilterValueLabel(typeFilter, types)
    val destinationText = trainFilterValueLabel(destinationFilter, destinations)
    val originText = trainFilterValueLabel(originFilter, TrainOriginFilters)
    return "種別：$typeText｜行き先：$destinationText｜始発：$originText"
}

private fun trainFilterValueLabel(selected: Set<String>?, all: List<String>): String {
    if (selected == null) {
        return "すべて"
    }
    val names = all.filter { it in selected }
    return when {
        names.isEmpty() -> "なし"
        names.size == all.size && all.isNotEmpty() -> "すべて"
        else -> names.joinToString("・")
    }
}
