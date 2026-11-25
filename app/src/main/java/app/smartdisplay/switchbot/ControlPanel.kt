package app.smartdisplay.switchbot

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.smartdisplay.util.AppToastHost
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.showOsAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KeyShape = RoundedCornerShape(22.dp)
private val PanelShape = RoundedCornerShape(32.dp)
private val KeyColor = Color.White.copy(alpha = 0.1f)
private val KeyBorder = Color.White.copy(alpha = 0.16f)
private val LocalTapSound = staticCompositionLocalOf<() -> Unit> { {} }
private const val PowerRefreshDelayMillis = 2_000L

@Composable
fun ControlPanel(
    client: SwitchBotClient,
    inventory: SwitchBotInventory?,
    onInventory: (SwitchBotInventory) -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf<ControlCategory?>(null) }
    var target by remember { mutableStateOf<ControlTarget?>(null) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var lightPower by remember { mutableStateOf<Boolean?>(null) }
    var powerEpoch by remember { mutableIntStateOf(0) }
    val onDevices = category != null

    BackHandler(enabled = onDevices && target == null && !busy) {
        onTapSound()
        category = null
    }

    fun close(playSound: Boolean = true) {
        if (busy) {
            return
        }
        if (playSound) {
            onTapSound()
        }
        visible = false
        scope.launch {
            delay(220)
            target = null
        }
    }

    fun readPower(deviceName: String, index: SwitchBotInventory?): Boolean? {
        val id = index?.deviceIds[deviceName] ?: return null
        return runCatching {
            client.fetchPowerOn(switchBotToken, switchBotSecret, id)
        }.getOrNull()
    }

    fun refreshPowerLater(deviceName: String, index: SwitchBotInventory?) {
        val epoch = ++powerEpoch
        scope.launch {
            delay(PowerRefreshDelayMillis)
            if (epoch != powerEpoch || target?.powerStatusDeviceName() != deviceName) {
                return@launch
            }
            val power = withContext(Dispatchers.IO) {
                readPower(deviceName, index)
            } ?: return@launch
            if (epoch != powerEpoch || target?.powerStatusDeviceName() != deviceName) {
                return@launch
            }
            lightPower = power
        }
    }

    fun perform(action: ControlAction, onSuccess: () -> Unit = {}) {
        if (busy || !switchBotConfigured) {
            return
        }
        val statusDevice = (action as? ControlAction.Device)?.deviceName
            ?.takeIf { it == target?.powerStatusDeviceName() }
        busy = true
        scope.launch {
            var fetched: SwitchBotInventory? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val index = inventory ?: client.fetchInventory(switchBotToken, switchBotSecret).also {
                        fetched = it
                    }
                    when (action) {
                        is ControlAction.Device -> {
                            val id = index.deviceIds[action.deviceName] ?: error("機器が見つかりません")
                            client.sendCommand(
                                token = switchBotToken,
                                secret = switchBotSecret,
                                deviceId = id,
                                command = action.command,
                                parameter = action.parameter,
                                commandType = action.commandType,
                            )
                        }
                        is ControlAction.Scene -> {
                            val id = index.sceneIds[action.sceneName] ?: error("シーンが見つかりません")
                            client.executeScene(switchBotToken, switchBotSecret, id)
                        }
                    }
                }
            }
            fetched?.let(onInventory)
            result.onSuccess {
                onSuccess()
            }.onFailure { error ->
                showOsAlert(error.message?.takeIf { it.isNotBlank() } ?: "操作に失敗しました")
                if (InternetConnectivity.hasInternet()) {
                    onErrorSound()
                }
            }
            if (statusDevice != null && result.isSuccess) {
                refreshPowerLater(statusDevice, fetched ?: inventory)
            }
            busy = false
        }
    }

    LaunchedEffect(target) {
        val epoch = ++powerEpoch
        lightPower = null
        val deviceName = target?.powerStatusDeviceName() ?: return@LaunchedEffect
        val index = inventory ?: withContext(Dispatchers.IO) {
            runCatching { client.fetchInventory(switchBotToken, switchBotSecret) }.getOrNull()
        }
        if (epoch != powerEpoch) {
            return@LaunchedEffect
        }
        if (index != null && inventory == null) {
            onInventory(index)
        }
        val power = withContext(Dispatchers.IO) {
            readPower(deviceName, index)
        }
        if (epoch == powerEpoch) {
            lightPower = power
        }
    }

    fun selectCategory(next: ControlCategory) {
        if (busy) {
            return
        }
        onTapSound()
        if (next == category) {
            return
        }
        category = next
        if (target != null) {
            close(playSound = false)
        }
    }

    AnimatedContent(
        targetState = onDevices,
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds(),
        transitionSpec = {
            if (targetState) {
                (slideInVertically(tween(280)) { height -> height } + fadeIn(tween(220)))
                    .togetherWith(slideOutVertically(tween(280)) { height -> -height / 5 } + fadeOut(tween(160)))
            } else {
                (slideInVertically(tween(280)) { height -> -height / 5 } + fadeIn(tween(220)))
                    .togetherWith(slideOutVertically(tween(280)) { height -> height } + fadeOut(tween(160)))
            }
        },
        label = "controlPage",
    ) { devices ->
        if (!devices) {
            CategoryGrid(onSelect = { selectCategory(it) })
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryBar(
                    selected = category,
                    onSelect = { selectCategory(it) },
                )
                val selected = category
                if (selected != null) {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                        },
                        label = "controlDevices",
                    ) { current ->
                        val section = ControlSections.first { it.category == current }
                        DeviceRow(
                            targets = section.targets,
                            onOpen = { next ->
                                if (!busy) {
                                    onTapSound()
                                    target = next
                                    visible = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(target) {
        if (target != null) {
            visible = true
        }
    }

    CompositionLocalProvider(LocalTapSound provides onTapSound) {
        val current = target
        if (current != null) {
            RemoteDialog(
                target = current,
                visible = visible,
                powerOn = lightPower,
                onClose = { close() },
                onPress = { action, onSuccess -> perform(action, onSuccess) },
            )
        }
        if (busy) {
            OperationLoading()
        }
    }
}

@Composable
private fun CategoryGrid(
    onSelect: (ControlCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ControlSections.chunked(3).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { section ->
                    CategoryPanel(
                        label = section.category.label,
                        onClick = { onSelect(section.category) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryPanel(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, KeyBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun CategoryBar(
    selected: ControlCategory?,
    onSelect: (ControlCategory) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ControlSections.forEach { item ->
            val active = item.category == selected
            Text(
                text = item.category.label,
                color = if (active) Color.Black else Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (active) Color.White else Color.White.copy(alpha = 0.1f))
                    .clickable { onSelect(item.category) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(
    targets: List<ControlTarget>,
    onOpen: (ControlTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        targets.chunked(3).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { item ->
                    DeviceCard(
                        title = item.title,
                        onClick = { onOpen(item) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, KeyBorder, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RemoteDialog(
    target: ControlTarget,
    visible: Boolean,
    powerOn: Boolean?,
    onClose: () -> Unit,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    val shift by animateDpAsState(
        targetValue = if (visible) 0.dp else 36.dp,
        animationSpec = tween(durationMillis = 240),
        label = "remoteShift",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "remoteAlpha",
    )
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 1040.dp)
                    .heightIn(max = 680.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = shift.toPx()
                        val scale = 0.96f + 0.04f * alpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(PanelShape)
                    .background(Color(0xFF161616))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), PanelShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.title,
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (powerOn != null) {
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (powerOn) Color.White else Color.White.copy(alpha = 0.28f),
                                        ),
                                )
                                Text(
                                    text = if (powerOn) "ON" else "OFF",
                                    color = if (powerOn) Color.White else Color.White.copy(alpha = 0.55f),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                    Text(
                        text = "閉じる",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                RemoteBody(target = target, onPress = onPress)
            }
            AppToastHost()
        }
    }
}

@Composable
private fun RemoteBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    when (target.layout) {
        RemoteLayout.BulbToggle -> {
            val key = target.key("toggle")
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PowerButton(label = key.label, onClick = { onPress(key.action) {} })
            }
        }
        RemoteLayout.LivingLight -> {
            val key = target.key("power")
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PowerButton(label = key.label, onClick = { onPress(key.action) {} })
            }
        }
        RemoteLayout.BedroomLight -> BedroomLightBody(target, onPress)
        RemoteLayout.CurtainPair -> PairBody(
            primary = target.key("open"),
            secondary = target.key("close"),
            onPress = onPress,
        )
        RemoteLayout.CurtainBatch -> GridBody(
            keys = target.keys,
            columns = 2,
            onPress = onPress,
            minHeight = 96.dp,
        )
        RemoteLayout.Television -> TelevisionBody(target, onPress)
        RemoteLayout.LivingSelector -> LivingSelectorBody(target, onPress)
        RemoteLayout.DeskSelector -> DeskSelectorBody(target, onPress)
        RemoteLayout.Speaker -> SpeakerBody(target, onPress)
        RemoteLayout.Fan -> FanBody(target, onPress)
        RemoteLayout.AirConditioner -> AirConditionerBody(onPress)
        RemoteLayout.Lock -> PairBody(
            primary = target.key("lock"),
            secondary = target.key("unlock"),
            onPress = onPress,
            minHeight = 140.dp,
        )
        RemoteLayout.ModePair -> PairBody(
            primary = target.keys[0],
            secondary = target.keys[1],
            onPress = onPress,
            minHeight = 120.dp,
        )
    }
}

@Composable
private fun BedroomLightBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PairBody(target.key("on"), target.key("off"), onPress, minHeight = 96.dp)
        GridBody(
            keys = listOf(target.key("up"), target.key("down"), target.key("night"), target.key("full")),
            columns = 4,
            onPress = onPress,
        )
    }
}

@Composable
private fun TelevisionBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    var powerOn by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PowerButton(label = "電源") {
                val next = !powerOn
                onPress(ControlAction.Device("テレビ", if (next) "turnOn" else "turnOff")) {
                    powerOn = next
                }
            }
            KeyButton(label = target.key("input").label, onClick = { onPress(target.key("input").action) {} })
        }
        Dpad(target, onPress)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyButton(
                label = "＋",
                caption = "音量",
                onClick = { onPress(target.key("volUp").action) {} },
                modifier = Modifier.width(96.dp).height(88.dp),
            )
            KeyButton(
                label = "−",
                caption = "音量",
                onClick = { onPress(target.key("volDown").action) {} },
                modifier = Modifier.width(96.dp).height(88.dp),
            )
            KeyButton(
                label = target.key("mute").label,
                onClick = { onPress(target.key("mute").action) {} },
                modifier = Modifier.width(96.dp).height(88.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KeyButton(
                label = target.key("chromecast").label,
                onClick = { onPress(target.key("chromecast").action) {} },
                modifier = Modifier.fillMaxWidth(0.75f).height(88.dp),
            )
            KeyButton(
                label = target.key("allOff").label,
                onClick = { onPress(target.key("allOff").action) {} },
                modifier = Modifier.fillMaxWidth(0.75f).height(88.dp),
            )
        }
    }
}

@Composable
private fun Dpad(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NavKey(target.key("up").label) { onPress(target.key("up").action) {} }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NavKey(target.key("left").label) { onPress(target.key("left").action) {} }
            NavKey(target.key("ok").label, emphasized = true) { onPress(target.key("ok").action) {} }
            NavKey(target.key("right").label) { onPress(target.key("right").action) {} }
        }
        NavKey(target.key("down").label) { onPress(target.key("down").action) {} }
    }
}

@Composable
private fun LivingSelectorBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    val inputs = target.keys.filter { it.id.length == 2 }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PowerButton(label = "電源") { onPress(target.key("power").action) {} }
        }
        InputBand("A", inputs.filter { it.id.startsWith("A") }, onPress)
        InputBand("B", inputs.filter { it.id.startsWith("B") }, onPress)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(target.key("livingCast"), target.key("bedCast")).forEach { key ->
                KeyButton(
                    label = key.label,
                    onClick = { onPress(key.action) {} },
                    modifier = Modifier.weight(1f).height(72.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBand(
    band: String,
    keys: List<RemoteKey>,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = band, color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            keys.forEach { key ->
                KeyButton(
                    label = key.label,
                    caption = key.caption,
                    onClick = { onPress(key.action) {} },
                    modifier = Modifier.weight(1f).height(78.dp),
                )
            }
        }
    }
}

@Composable
private fun DeskSelectorBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        target.keys.chunked(4).forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Out${index + 1}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(64.dp),
                )
                row.forEach { key ->
                    KeyButton(
                        label = key.label,
                        onClick = { onPress(key.action) {} },
                        modifier = Modifier.weight(1f).height(72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PowerButton(label = "電源") { onPress(target.key("power").action) {} }
            KeyButton(
                label = "＋",
                caption = "音量",
                onClick = { onPress(target.key("volUp").action) {} },
                modifier = Modifier.weight(1f).height(84.dp),
            )
            KeyButton(
                label = "−",
                caption = "音量",
                onClick = { onPress(target.key("volDown").action) {} },
                modifier = Modifier.weight(1f).height(84.dp),
            )
            KeyButton(
                label = target.key("mute").label,
                onClick = { onPress(target.key("mute").action) {} },
                modifier = Modifier.weight(1f).height(84.dp),
            )
        }
        GridBody(
            keys = listOf("prev", "play", "pause", "stop", "next").map { target.key(it) },
            columns = 5,
            onPress = onPress,
        )
        GridBody(
            keys = listOf("radio", "sd", "cd", "ipod").map { target.key(it) },
            columns = 4,
            onPress = onPress,
        )
    }
}

@Composable
private fun FanBody(
    target: ControlTarget,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PowerButton(label = "電源") { onPress(target.key("power").action) {} }
        }
        GridBody(
            keys = listOf("speed", "mode").map { target.key(it) },
            columns = 2,
            onPress = onPress,
            minHeight = 84.dp,
        )
    }
}

@Composable
private fun AirConditionerBody(
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    var temperature by remember { mutableIntStateOf(26) }
    var mode by remember { mutableIntStateOf(2) }
    var fan by remember { mutableIntStateOf(1) }
    var powerOn by remember { mutableStateOf(true) }
    val modes = listOf(1 to "自動", 2 to "冷房", 3 to "除湿", 4 to "送風", 5 to "暖房")
    val fans = listOf(1 to "自動", 2 to "弱", 3 to "中", 4 to "強")

    fun send(temp: Int, nextMode: Int, nextFan: Int, nextPower: Boolean, commit: () -> Unit) {
        onPress(
            ControlAction.Device(
                deviceName = "エアコン",
                command = "setAll",
                parameter = "$temp,$nextMode,$nextFan,${if (nextPower) "on" else "off"}",
            ),
        ) {
            commit()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PowerButton(label = if (powerOn) "入" else "切") {
                val next = !powerOn
                send(temperature, mode, fan, next) { powerOn = next }
            }
            KeyButton(
                label = "−",
                onClick = {
                    val next = (temperature - 1).coerceAtLeast(16)
                    send(next, mode, fan, true) {
                        temperature = next
                        powerOn = true
                    }
                },
                modifier = Modifier.size(84.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    text = "$temperature℃",
                    color = Color.White,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = modes.first { it.first == mode }.second + "  ·  風量 " + fans.first { it.first == fan }.second,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 16.sp,
                )
            }
            KeyButton(
                label = "＋",
                onClick = {
                    val next = (temperature + 1).coerceAtMost(30)
                    send(next, mode, fan, true) {
                        temperature = next
                        powerOn = true
                    }
                },
                modifier = Modifier.size(84.dp),
            )
        }
        ChoiceRow(label = "モード", options = modes, selected = mode) { next ->
            send(temperature, next, fan, true) {
                mode = next
                powerOn = true
            }
        }
        ChoiceRow(label = "風量", options = fans, selected = fan) { next ->
            send(temperature, mode, next, true) {
                fan = next
                powerOn = true
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (value, name) ->
                KeyButton(
                    label = name,
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f).height(56.dp),
                )
            }
        }
    }
}

@Composable
private fun PairBody(
    primary: RemoteKey,
    secondary: RemoteKey,
    onPress: (ControlAction, () -> Unit) -> Unit,
    minHeight: androidx.compose.ui.unit.Dp = 120.dp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(primary, secondary).forEach { key ->
            KeyButton(
                label = key.label,
                caption = key.caption,
                emphasize = key == primary,
                onClick = { onPress(key.action) {} },
                modifier = Modifier.weight(1f).height(minHeight),
            )
        }
    }
}

@Composable
private fun GridBody(
    keys: List<RemoteKey>,
    columns: Int,
    onPress: (ControlAction, () -> Unit) -> Unit,
    minHeight: androidx.compose.ui.unit.Dp = 72.dp,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        keys.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { key ->
                    KeyButton(
                        label = key.label,
                        caption = key.caption,
                        onClick = { onPress(key.action) {} },
                        modifier = Modifier.weight(1f).height(minHeight),
                    )
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NavKey(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val size = if (emphasized) 84.dp else 72.dp
    KeyButton(
        label = label,
        emphasize = emphasized,
        onClick = onClick,
        modifier = Modifier.size(size),
    )
}

@Composable
private fun PowerButton(
    label: String,
    onClick: () -> Unit,
) {
    val playTap = LocalTapSound.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .clickable {
                playTap()
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PowerGlyph(color = Color.White)
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PowerGlyph(color: Color) {
    Canvas(modifier = Modifier.size(34.dp)) {
        val strokeWidth = 3.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = (size.minDimension - strokeWidth) / 2f
        val gap = 72f
        drawArc(
            color = color,
            startAngle = 270f + gap / 2f,
            sweepAngle = 360f - gap,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(centerX - radius, centerY - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = stroke,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(centerX, centerY),
            end = androidx.compose.ui.geometry.Offset(centerX, centerY - radius),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    selected: Boolean = false,
    emphasize: Boolean = false,
) {
    val playTap = LocalTapSound.current
    val background = when {
        selected -> Color.White
        emphasize -> Color.White.copy(alpha = 0.16f)
        else -> KeyColor
    }
    val foreground = if (selected) Color.Black else Color.White
    Column(
        modifier = modifier
            .clip(KeyShape)
            .background(background)
            .border(1.dp, if (selected) Color.White else KeyBorder, KeyShape)
            .clickable {
                playTap()
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = if (caption == null) 20.sp else 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(
                text = caption,
                color = foreground.copy(alpha = 0.62f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun OperationLoading() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    text = "操作しています",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
    }
}
