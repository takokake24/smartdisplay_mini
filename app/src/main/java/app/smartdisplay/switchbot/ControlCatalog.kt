package app.smartdisplay.switchbot

enum class ControlCategory(val label: String) {
    Light("ライト"),
    Curtain("カーテン"),
    Av("テレビ・セレクタ・音響"),
    FanAircon("扇風機・エアコン"),
    Lock("ロック"),
    Mode("モード切替"),
}

enum class RemoteLayout {
    BulbToggle,
    LivingLight,
    BedroomLight,
    CurtainPair,
    CurtainBatch,
    Television,
    LivingSelector,
    DeskSelector,
    Speaker,
    Fan,
    AirConditioner,
    Lock,
    ModePair,
}

sealed class ControlAction {
    data class Device(
        val deviceName: String,
        val command: String,
        val parameter: String = "default",
        val commandType: String = "command",
    ) : ControlAction()

    data class Scene(val sceneName: String) : ControlAction()
}

data class RemoteKey(
    val id: String,
    val label: String,
    val caption: String? = null,
    val action: ControlAction,
)

data class ControlTarget(
    val title: String,
    val layout: RemoteLayout,
    val keys: List<RemoteKey> = emptyList(),
    val deviceName: String? = null,
)

data class ControlSection(
    val category: ControlCategory,
    val targets: List<ControlTarget>,
)

fun ControlTarget.key(id: String): RemoteKey = keys.first { it.id == id }

/** 開閉を個別に見るカーテン。一括シーンは状態を持たない。 */
fun curtainDeviceNames(): List<String> = listOf(LivingCurtain, BedCurtain, DeskCurtain)

/** 電源の ON/OFF を状態取得できる可能性があるライト。 */
fun ControlTarget.powerStatusDeviceName(): String? {
    val reportsPower = layout == RemoteLayout.BulbToggle ||
        layout == RemoteLayout.LivingLight ||
        layout == RemoteLayout.BedroomLight
    if (!reportsPower) {
        return null
    }
    return keys.firstNotNullOfOrNull { key ->
        (key.action as? ControlAction.Device)?.deviceName
    }
}

private fun device(
    deviceName: String,
    command: String,
    parameter: String = "default",
    commandType: String = "command",
) = ControlAction.Device(deviceName, command, parameter, commandType)

private fun custom(deviceName: String, button: String) =
    ControlAction.Device(deviceName, button, "default", "customize")

/** 赤外線リモコン。表示名ではなく、SwitchBot のキー番号を customize で送る。 */
private fun ir(deviceName: String, keyNumber: Int) =
    ControlAction.Device(deviceName, keyNumber.toString(), "default", "customize")

private fun scene(name: String) = ControlAction.Scene(name)

private fun key(
    id: String,
    label: String,
    action: ControlAction,
    caption: String? = null,
) = RemoteKey(id, label, caption, action)

private const val LivingLight = "リビングライト"
private const val BedroomLight = "寝室ライト"
private const val LivingCurtain = "リビングカーテン"
private const val BedCurtain = "ベッドカーテン"
private const val DeskCurtain = "デスク側カーテン"
private const val Television = "テレビ"
private const val LivingSelector = "HDMIセレクター"
private const val DeskSelector = "HDMIセレクター（デスク）"
private const val Speaker = "スピーカー"
private const val Fan = "扇風機"
private const val Lock = "ロックPro"

/** SwitchBot 上のボタン名。表示名とは括弧内の表記が一部違う。 */
private fun livingSelectorCommand(label: String, caption: String): String {
    val name = when (label) {
        "A6", "B6" -> "2nd selector"
        else -> caption
    }
    return "$label（$name）"
}

private val livingInputs = listOf(
    "A1" to "Nintendo Switch 2",
    "A2" to "Retro freak",
    "A3" to "Nintendo WiiU",
    "A4" to "Playstation 3",
    "A5" to "Playstation 5",
    "A6" to "2nd Selector",
    "B1" to "Nintendo Switch 2",
    "B2" to "Retro freak",
    "B3" to "Nintendo WiiU",
    "B4" to "Playstation 3",
    "B5" to "Playstation 5",
    "B6" to "2nd Selector",
)

val ControlSections: List<ControlSection> = listOf(
    ControlSection(
        category = ControlCategory.Light,
        targets = listOf(
            ControlTarget(
                title = "リビングライト",
                layout = RemoteLayout.LivingLight,
                keys = listOf(
                    key("power", "電源", ir(LivingLight, 2)),
                ),
            ),
            ControlTarget(
                title = "寝室ライト",
                layout = RemoteLayout.BedroomLight,
                keys = listOf(
                    key("on", "点灯", device(BedroomLight, "turnOn")),
                    key("off", "消灯", device(BedroomLight, "turnOff")),
                    key("up", "明るく", device(BedroomLight, "brightnessUp")),
                    key("down", "暗く", device(BedroomLight, "brightnessDown")),
                    key("night", "常夜灯", custom(BedroomLight, "常夜灯")),
                    key("full", "全灯", custom(BedroomLight, "全灯")),
                ),
            ),
            bulb("キッチンライト"),
            bulb("トイレ浴室ライト"),
            bulb("玄関ライト"),
        ),
    ),
    ControlSection(
        category = ControlCategory.Curtain,
        targets = listOf(
            ControlTarget(
                title = "一括操作",
                layout = RemoteLayout.CurtainBatch,
                keys = listOf(
                    key("open", "開く", scene("カーテン開")),
                    key("close", "閉じる", scene("カーテン閉")),
                    key("sunriseOn", "日の出オープン有効", scene("カーテンオープン有効化")),
                    key("sunriseOff", "日の出オープン無効", scene("カーテンオープン無効化")),
                ),
            ),
            ControlTarget(
                title = "リビングカーテン",
                layout = RemoteLayout.CurtainPair,
                keys = listOf(
                    // 0 が全開、100 が全閉。34 は「34%まで開く」の位置。
                    key("open", "開く", device(LivingCurtain, "setPosition", "0,ff,34")),
                    key("close", "閉じる", device(LivingCurtain, "turnOff")),
                ),
            ),
            ControlTarget(
                title = "ベッドカーテン",
                layout = RemoteLayout.CurtainPair,
                keys = listOf(
                    key("open", "開く", device(BedCurtain, "turnOn")),
                    key("close", "閉じる", device(BedCurtain, "turnOff")),
                ),
            ),
            ControlTarget(
                title = "デスクカーテン",
                layout = RemoteLayout.CurtainPair,
                keys = listOf(
                    key("open", "開く", device(DeskCurtain, "turnOn")),
                    key("close", "閉じる", device(DeskCurtain, "turnOff")),
                ),
            ),
        ),
    ),
    ControlSection(
        category = ControlCategory.Av,
        targets = listOf(
            ControlTarget(
                title = "テレビ",
                layout = RemoteLayout.Television,
                keys = listOf(
                    key("power", "電源", device(Television, "turnOn")),
                    key("volUp", "音量＋", device(Television, "volumeAdd")),
                    key("volDown", "音量−", device(Television, "volumeSub")),
                    key("mute", "消音", device(Television, "setMute")),
                    key("input", "入力切替", custom(Television, "入力切替")),
                    key("up", "▲", ir(Television, 43)),
                    key("down", "▼", ir(Television, 49)),
                    key("left", "◀", ir(Television, 45)),
                    key("right", "▶", ir(Television, 47)),
                    key("ok", "決定", ir(Television, 41)),
                    key("chromecast", "Chromecastモード", scene("ChromecastモードON")),
                    key("allOff", "TV・セレクタOFF", scene("テレビ・セレクタオフ")),
                ),
            ),
            ControlTarget(
                title = "リビングセレクタ",
                layout = RemoteLayout.LivingSelector,
                keys = buildList {
                    add(key("power", "電源", custom(LivingSelector, "電源")))
                    livingInputs.forEach { (label, caption) ->
                        add(key(label, label, custom(LivingSelector, livingSelectorCommand(label, caption)), caption))
                    }
                    add(key("livingCast", "リビングChromecast", scene("セレクタA1B6")))
                    add(key("bedCast", "寝室Chromecast", scene("寝室Chromecast")))
                },
            ),
            ControlTarget(
                title = "寝室セレクター",
                layout = RemoteLayout.DeskSelector,
                keys = buildList {
                    for (output in 1..2) {
                        for (input in 1..4) {
                            val command = "Out$output IN$input"
                            add(key("o${output}i$input", "IN$input", custom(DeskSelector, command)))
                        }
                    }
                },
            ),
            ControlTarget(
                title = "スピーカー",
                layout = RemoteLayout.Speaker,
                keys = listOf(
                    key("power", "電源", ir(Speaker, 11)),
                    key("volUp", "音量＋", ir(Speaker, 13)),
                    key("volDown", "音量−", ir(Speaker, 17)),
                    key("mute", "消音", ir(Speaker, 15)),
                    key("next", "次", ir(Speaker, 29)),
                    key("prev", "前", ir(Speaker, 25)),
                    key("play", "再生", custom(Speaker, "再生")),
                    key("pause", "一時停止", custom(Speaker, "一時停止")),
                    key("stop", "停止", custom(Speaker, "停止")),
                    key("radio", "FM/AM/AUX", custom(Speaker, "FM/AM/AUX")),
                    key("sd", "SD", custom(Speaker, "SD")),
                    key("cd", "CD", custom(Speaker, "CD")),
                    key("ipod", "iPod", custom(Speaker, "iPod")),
                ),
            ),
        ),
    ),
    ControlSection(
        category = ControlCategory.FanAircon,
        targets = listOf(
            ControlTarget(
                title = "扇風機",
                layout = RemoteLayout.Fan,
                keys = listOf(
                    key("power", "電源", ir(Fan, 1)),
                    key("speed", "風量", custom(Fan, "風量")),
                    key("mode", "モード", custom(Fan, "モード")),
                ),
            ),
            ControlTarget(
                title = "エアコン",
                layout = RemoteLayout.AirConditioner,
                deviceName = "エアコン",
            ),
        ),
    ),
    ControlSection(
        category = ControlCategory.Lock,
        targets = listOf(
            ControlTarget(
                title = "玄関ドア",
                layout = RemoteLayout.Lock,
                keys = listOf(
                    key("lock", "施錠", device(Lock, "lock")),
                    key("unlock", "解錠", device(Lock, "unlock")),
                ),
            ),
        ),
    ),
    ControlSection(
        category = ControlCategory.Mode,
        targets = listOf(
            ControlTarget(
                title = "警備",
                layout = RemoteLayout.ModePair,
                keys = listOf(
                    key("start", "警備開始", scene("警備開始")),
                    key("end", "警備終了", scene("警備終了")),
                ),
            ),
            ControlTarget(
                title = "外出",
                layout = RemoteLayout.ModePair,
                keys = listOf(
                    key("home", "在宅モード", scene("在宅モード")),
                    key("away", "外出モード", scene("外出モード")),
                ),
            ),
        ),
    ),
)

private fun bulb(name: String) = ControlTarget(
    title = name,
    layout = RemoteLayout.BulbToggle,
    keys = listOf(key("toggle", "ON/OFF", device(name, "toggle"))),
)
