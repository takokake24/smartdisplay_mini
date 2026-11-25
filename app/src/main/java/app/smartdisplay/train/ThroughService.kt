package app.smartdisplay.train

internal const val TozaiThroughNote = "東西線直通"
internal const val ToyoThroughNote = "東西線・東葉高速線直通"

/** 行き先だけで東西線直通だと分かる駅。 */
internal val TozaiThroughDestinations = setOf(
    "落合", "高田馬場", "早稲田", "神楽坂", "九段下", "竹橋", "大手町", "日本橋", "茅場町", "門前仲町", "木場", "東陽町", "南砂町", "西葛西", "葛西", "浦安", "南行徳",
    "行徳", "妙典", "原木中山",
)

/** 行き先だけで東葉高速線まで直通だと分かる駅。 */
internal val ToyoThroughDestinations = setOf("東海神", "飯山満", "北習志野", "船橋日大前", "八千代緑が丘", "八千代中央", "村上", "東葉勝田台")

/** 御茶ノ水から先の総武線。ここ行きは総武線直通。 */
private val SobuThroughDestinations = setOf(
    "秋葉原", "浅草橋", "両国", "錦糸町", "亀戸", "平井", "新小岩", "小岩", "市川", "本八幡", "下総中山", "船橋", "東船橋", "津田沼", "幕張本郷", "幕張", "新検見川",
    "稲毛", "西千葉", "千葉",
)

private val OmeLineDestinations = setOf(
    "西立川", "東中神", "中神", "昭島", "拝島", "牛浜", "福生", "羽村", "小作", "河辺", "東青梅", "青梅", "宮ノ平", "日向和田", "石神前", "二俣尾", "軍畑", "沢井",
    "御嶽", "川井", "古里", "鳩ノ巣", "白丸", "奥多摩",
)

private val FujiKyukoDestinations = setOf(
    "上大月", "谷村町", "都留市", "十日市場", "東桂", "三つ峠", "寿", "葭池温泉前", "下吉田", "月江寺", "富士山", "富士急ハイランド", "河口湖",
)

private val ShinonoiLineDestinations = setOf(
    "広丘", "村井", "平田", "南松本", "松本", "田沢", "明科", "西条", "坂北", "聖高原", "冠着", "姨捨", "稲荷山", "篠ノ井",
)

private val OitoLineDestinations = setOf(
    "北松本", "島内", "島高松", "梓橋", "一日市場", "中萱", "南豊科", "豊科", "柏矢町", "穂高", "有明", "安曇追分", "細野", "北細野", "信濃松川", "安曇沓掛", "信濃常盤",
    "南大町", "信濃大町", "北大町", "信濃木崎", "稲尾", "海ノ口", "簗場", "ヤナバスキー場前", "南神城", "神城", "飯森", "白馬", "信濃森上", "白馬大池", "千国", "南小谷",
    "中土", "北小谷", "平岩", "小滝", "根知", "頸城大野", "姫川", "糸魚川",
)

private val KawagoeLineDestinations = setOf("日進", "西大宮", "指扇", "南古谷", "川越", "西川越", "的場", "笠幡", "武蔵高萩", "高麗川")

private val RinkaiLineDestinations = setOf("大井町", "品川シーサイド", "天王洲アイル", "東京テレポート", "国際展示場", "東雲", "新木場")

private val SotetsuLineDestinations = setOf(
    "羽沢横浜国大", "西谷", "鶴ケ峰", "鶴ヶ峰", "二俣川", "希望ケ丘", "希望ヶ丘", "三ツ境", "瀬谷", "大和", "相模大塚", "さがみ野", "かしわ台", "海老名", "南万騎が原",
    "緑園都市", "弥生台", "いずみ野", "いずみ中央", "ゆめが丘", "湘南台",
)

private val UtsunomiyaLineDestinations = setOf(
    "土呂", "東大宮", "蓮田", "白岡", "新白岡", "久喜", "東鷲宮", "栗橋", "古河", "野木", "間々田", "小山", "小金井", "自治医大", "石橋", "雀宮", "宇都宮", "岡本",
    "宝積寺", "氏家", "蒲須坂", "片岡", "矢板", "野崎", "西那須野", "那須塩原", "黒磯",
)

private val TakasakiLineDestinations = setOf(
    "宮原", "上尾", "桶川", "北本", "鴻巣", "北鴻巣", "吹上", "行田", "熊谷", "籠原", "深谷", "岡部", "本庄", "神保原", "新町", "倉賀野", "高崎",
)

private val RyomoLineDestinations = setOf(
    "高崎問屋町", "井野", "新前橋", "前橋", "前橋大島", "駒形", "伊勢崎", "国定", "岩宿", "桐生", "小俣", "山前", "足利", "あしかがフラワーパーク", "富田", "佐野",
    "岩舟", "大平下", "栃木", "思川",
)

private val TokaidoLineDestinations = setOf(
    "藤沢", "辻堂", "茅ケ崎", "茅ヶ崎", "平塚", "大磯", "二宮", "国府津", "鴨宮", "小田原", "早川", "根府川", "真鶴", "湯河原", "熱海", "函南", "三島", "沼津",
)

private val NaritaLineDestinations = setOf(
    "成田",
    "空港第2ビル",
    "成田空港",
)

private val UchiboLineDestinations = setOf(
    "浜野", "八幡宿", "五井", "姉ケ崎", "姉ヶ崎", "長浦", "袖ケ浦", "巌根", "木更津", "君津", "青堀", "大貫", "佐貫町", "上総湊", "竹岡", "浜金谷", "保田",
    "安房勝山", "岩井", "富浦", "那古船形", "館山", "九重", "千倉", "千歳", "南三原", "和田浦", "江見", "太海",
)

private val SotoboLineDestinations = setOf(
    "本千葉", "鎌取", "誉田", "土気", "大網", "永田", "本納", "新茂原", "茂原", "八積", "上総一ノ宮", "東浪見", "太東", "長者町", "三門", "大原", "浪花", "御宿",
    "勝浦", "鵜原", "上総興津", "行川アイランド", "安房小湊",
)

private val YokosukaLineDestinations = setOf("北鎌倉", "鎌倉", "逗子", "東逗子", "田浦", "横須賀", "衣笠", "久里浜")

private val KeioSagamiharaDestinations = setOf(
    "京王多摩川", "京王稲田堤", "京王よみうりランド", "稲城", "若葉台", "京王永山", "京王多摩センター", "京王堀之内", "南大沢", "多摩境", "橋本",
)

private val KeioTakaoDestinations = setOf("京王片倉", "山田", "めじろ台", "狭間", "高尾", "高尾山口")

private val OdakyuTamaDestinations = setOf("五月台", "栗平", "黒川", "はるひ野", "小田急はるひ野", "小田急永山", "小田急多摩センター", "唐木田")

private val OdakyuEnoshimaDestinations = setOf(
    "東林間", "中央林間", "南林間", "鶴間", "大和", "桜ヶ丘", "桜ケ丘", "高座渋谷", "長後", "湘南台", "六会日大前", "善行", "藤沢本町", "藤沢", "本鵠沼", "鵠沼海岸",
    "片瀬江ノ島",
)

private val HakoneTozanDestinations = setOf("箱根板橋", "風祭", "入生田", "箱根湯本", "塔ノ沢", "大平台", "宮ノ下", "小涌谷", "彫刻の森", "強羅")

private val GotembaLineDestinations = setOf("松田", "東山北", "山北", "谷峨", "駿河小山", "足柄", "御殿場")

private val SeibuHaijimaDestinations = setOf("萩山", "小川", "東大和市", "玉川上水", "武蔵砂川", "西武立川", "拝島")

private val SeibuTamakoDestinations = setOf("八坂", "武蔵大和", "西武園", "多摩湖")

private val NegishiLineDestinations = setOf("桜木町", "関内", "石川町", "山手", "根岸", "磯子", "新杉田", "洋光台", "港南台", "本郷台", "大船")

private val ItsukaichiLineDestinations = setOf("熊川", "東秋留", "秋川", "武蔵引田", "武蔵増戸", "武蔵五日市")

private val ItoLineDestinations = setOf("来宮", "伊豆多賀", "網代", "宇佐美", "伊東")

private val IzukyuDestinations = setOf(
    "南伊東", "川奈", "富戸", "城ケ崎海岸", "城ヶ崎海岸", "伊豆高原", "伊豆大川", "伊豆北川", "伊豆熱川", "片瀬白田", "伊豆稲取", "今井浜海岸", "河津", "稲梓", "蓮台寺",
    "伊豆急下田",
)

private val SunzuDestinations = setOf("大場", "伊豆仁田", "原木", "韮山", "伊豆長岡", "田京", "大仁", "牧之郷", "修善寺")

private val YosanDestinations = setOf("宇野", "茶屋町", "児島", "坂出", "宇多津", "丸亀", "多度津", "高松")

private val DosanDestinations = setOf("琴平", "阿波池田", "土佐山田", "後免", "高知", "須崎", "窪川", "中村", "宿毛")

private val SaninDestinations = setOf("清音", "総社", "豪渓", "新見", "生山", "根雨", "伯耆大山", "米子", "安来", "松江", "玉造温泉", "宍道", "出雲市")

private val KashimaLineDestinations = setOf("十二橋", "潮来", "延方", "鹿島神宮", "鹿島サッカースタジアム")

private val NaritaLineLocalDestinations = setOf("酒々井", "久住", "滑河", "下総神崎", "大戸", "佐原", "香取")

private val ToganeLineDestinations = setOf("福俵", "東金", "求名", "成東")

private val JobanDestinations = setOf(
    "日暮里", "三河島", "南千住", "北千住", "綾瀬", "亀有", "金町", "松戸", "北松戸", "馬橋", "新松戸", "北小金", "南柏", "柏", "北柏", "我孫子", "天王台", "取手",
    "藤代", "龍ケ崎市", "龍ヶ崎市", "牛久", "ひたち野うしく", "荒川沖", "土浦", "神立", "高浜", "石岡", "羽鳥", "岩間", "友部", "内原", "赤塚", "偕楽園", "水戸",
    "勝田", "佐和", "東海", "大甕", "常陸多賀", "日立", "小木津", "十王", "高萩", "南中郷", "磯原", "大津港", "勿来", "植田", "泉", "湯本", "内郷", "いわき",
    "草野", "四ツ倉", "久ノ浜", "末続", "広野", "木戸", "竜田", "富岡", "夜ノ森", "大野", "双葉", "浪江", "桃内", "小高", "磐城太田", "原ノ町", "鹿島", "日立木",
    "相馬", "駒ケ嶺", "新地", "坂元", "山下", "浜吉田", "亘理", "逢隈", "岩沼", "館腰", "名取", "南仙台", "太子堂", "長町", "仙台", "東我孫子", "湖北", "新木",
    "布佐", "木下", "小林", "安食", "下総松崎", "成田", "空港第2ビル", "成田空港",
)

private val KeioGroups = setOf("3021", "3031")

private val TypeChangePattern = Regex(
    "(.+?)[−－\\-~〜](.+?)間は.*?" +
        "(各駅停車|区間急行|快速急行|通勤特急|通勤急行|通勤快速|通勤特快|" +
        "中央特快|青梅特快|特別快速|準急|急行|快速|特急|ライナー)で運転",
)

/** 上野東京ラインのうち、常磐線側の行き先。それ以外は宇都宮線・高崎線に出す。 */
internal fun trainListed(direction: TrainDirection, destinationName: String): Boolean {
    if (direction.board.isEmpty()) {
        return true
    }
    val name = trainDestinationLabel(destinationName)
    val joban = name in JobanDestinations
    return if (direction.board == "joban") joban else !joban
}

/** 行き先で分かる直通。さざなみ・わかしおは列車名も見る。分からないものは空。 */
internal fun throughServiceNote(
    direction: TrainDirection,
    destinationName: String,
    trainName: String = "",
): String? {
    val name = trainDestinationLabel(destinationName)
    if (name.isEmpty() || name == "不明") {
        return null
    }
    return when (direction.yahooGroupId) {
        "1100" -> chuoLocalUplineNote(direction, name)
        "1090" -> shinjukuChuoUpNote(direction, name, trainName)
        "1091" -> chuoRapidDownNote(name)
        "1171" -> tokaidoBranchNote(name)
        "7910" -> shonanNorthNote(name)
        "941" -> sobuRapidNote(name, trainName)
        "951" -> keiyoNote(name, trainName)
        "1151" -> if (name in NegishiLineDestinations) "根岸線直通" else null
        "1111" -> if (name in ItsukaichiLineDestinations) "五日市線直通" else null
        "1061" -> if (name in KawagoeLineDestinations) "川越線直通" else null
        "1060" -> saikyoSouthNote(name)
        "1051" -> shonanNorthNote(name)
        "1050" -> shonanSouthNote(name)
        "3021", "3031" -> keioNote(name)
        "3091" -> odakyuNote(name)
        "2881" -> seibuNote(name)
        else -> null
    }
}

/**
 * 停車駅を開いたときに足す種別。
 * 京王はこれから先で変わる区間、相鉄直通は相鉄線内の種別。
 */
internal fun trainDetailServiceNote(
    direction: TrainDirection,
    trip: TrainTrip,
    stops: List<TrainStop>,
    guideComment: String,
    displayName: String,
): String {
    val notes = listOf(
        keioTypeChangeNote(direction, trip, stops, guideComment),
        sotetsuInnerNote(direction, trip, displayName),
    )
    return notes.filter { it.isNotEmpty() }.joinToString(" ")
}

private fun chuoLocalUplineNote(direction: TrainDirection, name: String): String? {
    if (direction.isChuoLocalEast) {
        return when {
            name in ToyoThroughDestinations -> ToyoThroughNote
            name in TozaiThroughDestinations -> TozaiThroughNote
            name in SobuThroughDestinations -> "総武線直通"
            else -> null
        }
    }
    return if (name in SobuThroughDestinations || name == "西船橋") {
        "総武線直通"
    } else {
        null
    }
}

private fun shinjukuChuoUpNote(
    direction: TrainDirection,
    name: String,
    trainName: String,
): String? {
    if (direction.station != TrainStation.Shinjuku) {
        return null
    }
    return when {
        "さざなみ" in trainName || name in UchiboLineDestinations -> "総武線・内房線直通"
        "わかしお" in trainName || name in SotoboLineDestinations -> "総武線・外房線直通"
        else -> null
    }
}

private fun chuoRapidDownNote(name: String): String? {
    return when {
        name in OmeLineDestinations -> "青梅線直通"
        name in FujiKyukoDestinations -> "富士急行線直通"
        name in OitoLineDestinations -> "篠ノ井線・大糸線直通"
        name in ShinonoiLineDestinations -> "篠ノ井線直通"
        else -> null
    }
}

private fun saikyoSouthNote(name: String): String? {
    return when {
        name in SotetsuLineDestinations -> "相鉄線直通"
        name in RinkaiLineDestinations -> "りんかい線直通"
        else -> null
    }
}

private fun tokaidoBranchNote(name: String): String? {
    return when {
        name in IzukyuDestinations -> "伊東線・伊豆急行線直通"
        name in ItoLineDestinations -> "伊東線直通"
        name in SunzuDestinations -> "駿豆線直通"
        name in DosanDestinations -> "山陽線・宇野線・瀬戸大橋線・予讃線・土讃線直通"
        name in YosanDestinations -> "山陽線・宇野線・瀬戸大橋線・予讃線直通"
        name in SaninDestinations -> "山陽線・伯備線・山陰線直通"
        else -> null
    }
}

private fun sobuRapidNote(name: String, trainName: String): String? {
    return when {
        name in KashimaLineDestinations -> "成田線・鹿島線直通"
        name in NaritaLineDestinations || name in NaritaLineLocalDestinations -> "成田線直通"
        "さざなみ" in trainName || name in UchiboLineDestinations -> "内房線直通"
        "わかしお" in trainName || name in SotoboLineDestinations -> "外房線直通"
        else -> null
    }
}

private fun keiyoNote(name: String, trainName: String): String? {
    return when {
        name in ToganeLineDestinations -> "外房線・東金線直通"
        "さざなみ" in trainName || name in UchiboLineDestinations -> "内房線直通"
        "わかしお" in trainName || name in SotoboLineDestinations -> "外房線直通"
        else -> null
    }
}

private fun shonanSouthNote(name: String): String? {
    return when {
        name in NaritaLineDestinations -> "総武線・成田線直通"
        name in TokaidoLineDestinations -> "東海道線直通"
        name in YokosukaLineDestinations -> "横須賀線直通"
        else -> null
    }
}

private fun shonanNorthNote(name: String): String? {
    return when {
        name in RyomoLineDestinations -> "高崎線・両毛線直通"
        name in TakasakiLineDestinations -> "高崎線直通"
        name in UtsunomiyaLineDestinations -> "宇都宮線直通"
        else -> null
    }
}

private fun keioNote(name: String): String? {
    return when {
        name in KeioSagamiharaDestinations -> "相模原線直通"
        name == "府中競馬正門前" -> "競馬場線直通"
        name in KeioTakaoDestinations -> "高尾線直通"
        else -> null
    }
}

private fun odakyuNote(name: String): String? {
    return when {
        name in OdakyuTamaDestinations -> "多摩線直通"
        name in OdakyuEnoshimaDestinations -> "江ノ島線直通"
        name in HakoneTozanDestinations -> "箱根登山線直通"
        name in GotembaLineDestinations -> "御殿場線直通"
        else -> null
    }
}

private fun seibuNote(name: String): String? {
    return when {
        name in SeibuTamakoDestinations -> "多摩湖線直通"
        name in SeibuHaijimaDestinations -> "拝島線直通"
        else -> null
    }
}

private fun keioTypeChangeNote(
    direction: TrainDirection,
    trip: TrainTrip,
    stops: List<TrainStop>,
    guideComment: String,
): String {
    if (direction.yahooGroupId !in KeioGroups || guideComment.isBlank()) {
        return ""
    }
    val here = stops.indexOfFirst { it.isOrigin }
    if (here < 0) {
        return ""
    }
    val notes = linkedSetOf<String>()
    for (match in TypeChangePattern.findAll(guideComment)) {
        val from = trainDestinationLabel(match.groupValues[1].substringAfterLast('。'))
        val type = match.groupValues[3]
        val fromIndex = stops.indexOfFirst { it.name == from }
        if (fromIndex > here && type != trip.typeLabel) {
            notes += "${from}から$type"
        }
    }
    return notes.joinToString(" ")
}

private fun sotetsuInnerNote(
    direction: TrainDirection,
    trip: TrainTrip,
    displayName: String,
): String {
    if (direction.yahooGroupId != "1060") {
        return ""
    }
    if (throughServiceNote(direction, trip.destination) != "相鉄線直通") {
        return ""
    }
    val type = displayName.trim()
    if (type !in SotetsuInnerTypes || type == trip.typeLabel) {
        return ""
    }
    return "相鉄線内$type"
}

private val SotetsuInnerTypes = setOf("各駅停車", "快速", "通勤急行", "通勤特急", "急行", "特急")
