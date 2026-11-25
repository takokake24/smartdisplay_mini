package app.smartdisplay.switchbot

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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

private val DialogShape = RoundedCornerShape(32.dp)
private val SectionShape = RoundedCornerShape(24.dp)
private val KeyShape = RoundedCornerShape(18.dp)
private val KeyColor = Color.White.copy(alpha = 0.1f)
private val KeyBorder = Color.White.copy(alpha = 0.16f)
private val DialogColor = Color(0xFF161616)
private val KeyHeight = 72.dp
private val WideLayoutMin = 1000.dp

@Composable
fun QuickActionsEntry(
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var inventory by remember { mutableStateOf<SwitchBotInventory?>(null) }
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .width(240.dp)
            .heightIn(min = 138.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable(role = Role.Button) {
                onTapSound()
                open = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "クイック操作",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
    if (open) {
        QuickActionsDialog(
            initialInventory = inventory,
            onInventory = { inventory = it },
            onDismiss = { open = false },
            onTapSound = onTapSound,
            onErrorSound = onErrorSound,
        )
    }
}

@Composable
private fun QuickActionsDialog(
    initialInventory: SwitchBotInventory?,
    onInventory: (SwitchBotInventory) -> Unit,
    onDismiss: () -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
) {
    val client = remember { SwitchBotClient() }
    val scope = rememberCoroutineScope()
    var inventory by remember { mutableStateOf(initialInventory) }
    var busy by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var tvOn by remember { mutableStateOf(false) }
    val shift by animateDpAsState(
        targetValue = if (visible) 0.dp else 28.dp,
        animationSpec = tween(durationMillis = 220),
        label = "quickShift",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "quickAlpha",
    )

    LaunchedEffect(Unit) {
        visible = true
    }

    fun requestClose() {
        if (busy || closing) {
            return
        }
        closing = true
        onTapSound()
        visible = false
        scope.launch {
            delay(180)
            onDismiss()
        }
    }

    fun press(action: ControlAction, onSuccess: () -> Unit = {}) {
        if (busy || closing) {
            return
        }
        onTapSound()
        if (!switchBotConfigured) {
            showOsAlert("認証情報がありません")
            return
        }
        val cached = inventory
        busy = true
        scope.launch {
            var fetched: SwitchBotInventory? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val index = cached ?: client.fetchInventory(switchBotToken, switchBotSecret).also {
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
            fetched?.let {
                inventory = it
                onInventory(it)
            }
            result.onSuccess {
                onSuccess()
            }.onFailure { error ->
                showOsAlert(error.message?.takeIf { it.isNotBlank() } ?: "操作に失敗しました")
                if (InternetConnectivity.hasInternet()) {
                    onErrorSound()
                }
            }
            busy = false
        }
    }

    Dialog(
        onDismissRequest = { requestClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { requestClose() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val wide = maxWidth >= WideLayoutMin
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 1200.dp)
                    .heightIn(max = maxHeight * 0.92f)
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = shift.toPx()
                        val scale = 0.96f + 0.04f * alpha
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(DialogShape)
                    .background(DialogColor)
                    .border(1.dp, Color.White.copy(alpha = 0.14f), DialogShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                ) {
                    QuickHeader(onClose = { requestClose() })
                    QuickActionsBody(
                        wide = wide,
                        onPress = { action, onSuccess -> press(action, onSuccess) },
                        onTvPower = {
                            val next = !tvOn
                            press(tvPowerAction(next)) { tvOn = next }
                        },
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                if (busy) {
                    QuickBusyOverlay(Modifier.matchParentSize())
                }
            }
            AppToastHost()
        }
    }
}

@Composable
private fun QuickHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 26.dp, end = 22.dp, top = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "クイック操作",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "閉じる",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 18.sp,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClick = onClose)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun QuickActionsBody(
    wide: Boolean,
    onPress: (ControlAction, () -> Unit) -> Unit,
    onTvPower: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (wide) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1.22f)) {
                AvSection(onPress = onPress, onTvPower = onTvPower)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LightSection(onPress)
                CurtainSection(onPress)
                LockSection(onPress)
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AvSection(onPress = onPress, onTvPower = onTvPower)
            LightSection(onPress)
            CurtainSection(onPress)
            LockSection(onPress)
        }
    }
}

@Composable
private fun AvSection(
    onPress: (ControlAction, () -> Unit) -> Unit,
    onTvPower: () -> Unit,
) {
    val tv = controlTarget("テレビ")
    val selector = controlTarget("リビングセレクタ")
    val fan = controlTarget("扇風機")
    QuickSection(title = "テレビ・セレクタ・音響") {
        DeviceLabel("テレビ")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KeyHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickKey(
                label = "電源",
                onClick = onTvPower,
                modifier = Modifier
                    .weight(0.8f)
                    .fillMaxHeight(),
            )
            ActionKey(tv.key("volUp"), onPress, weight = 1f)
            ActionKey(tv.key("volDown"), onPress, weight = 1f)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KeyHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionKey(tv.key("chromecast"), onPress)
            ActionKey(tv.key("allOff"), onPress)
        }
        DeviceLabel(
            text = "リビングセレクタ",
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickKey(
                label = "電源",
                onClick = { onPress(selector.key("power").action) {} },
                modifier = Modifier
                    .width(96.dp)
                    .fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectorBand(selector, listOf("A1", "A5", "A6"), onPress)
                SelectorBand(selector, listOf("B1", "B5", "B6"), onPress)
            }
        }
        DeviceLabel(
            text = "扇風機",
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KeyHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionKey(fan.key("power"), onPress)
            ActionKey(fan.key("speed"), onPress)
        }
    }
}

@Composable
private fun ColumnScope.SelectorBand(
    target: ControlTarget,
    ids: List<String>,
    onPress: (ControlAction, () -> Unit) -> Unit,
) {
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ids.forEach { id ->
            ActionKey(target.key(id), onPress)
        }
    }
}

@Composable
private fun LightSection(onPress: (ControlAction, () -> Unit) -> Unit) {
    val living = controlTarget("リビングライト")
    val bedroom = controlTarget("寝室ライト")
    val kitchen = controlTarget("キッチンライト")
    QuickSection(title = "ライト") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DeviceButtons(title = "リビング", modifier = Modifier.weight(1f)) {
                QuickKey(
                    label = "ON/OFF",
                    onClick = { onPress(living.key("power").action) {} },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyHeight),
                )
            }
            DeviceButtons(title = "寝室", modifier = Modifier.weight(1.35f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyHeight),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionKey(bedroom.key("on"), onPress, emphasize = true)
                    ActionKey(bedroom.key("off"), onPress)
                }
            }
            DeviceButtons(title = "キッチンライト", modifier = Modifier.weight(1.05f)) {
                QuickKey(
                    label = kitchen.key("toggle").label,
                    onClick = { onPress(kitchen.key("toggle").action) {} },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(KeyHeight),
                )
            }
        }
    }
}

@Composable
private fun CurtainSection(onPress: (ControlAction, () -> Unit) -> Unit) {
    val curtains = listOf(
        "一括操作" to "一括操作",
        "リビング" to "リビングカーテン",
        "ベッド" to "ベッドカーテン",
        "デスク" to "デスクカーテン",
    )
    QuickSection(title = "カーテン") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            curtains.forEach { (label, title) ->
                val target = controlTarget(title)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = label,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    QuickKey(
                        label = target.key("open").label,
                        emphasize = true,
                        onClick = { onPress(target.key("open").action) {} },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    )
                    QuickKey(
                        label = target.key("close").label,
                        onClick = { onPress(target.key("close").action) {} },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LockSection(onPress: (ControlAction, () -> Unit) -> Unit) {
    val door = controlTarget("玄関ドア")
    QuickSection(title = "ロック") {
        DeviceLabel("玄関ドア")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KeyHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionKey(door.key("lock"), onPress, emphasize = true)
            ActionKey(door.key("unlock"), onPress)
        }
    }
}

@Composable
private fun QuickSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = {
            Text(
                text = title,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            content()
        },
    )
}

@Composable
private fun DeviceLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.88f),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun DeviceButtons(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        content()
    }
}

@Composable
private fun RowScope.ActionKey(
    key: RemoteKey,
    onPress: (ControlAction, () -> Unit) -> Unit,
    label: String = key.label,
    emphasize: Boolean = false,
    weight: Float = 1f,
) {
    QuickKey(
        label = label,
        caption = key.caption,
        emphasize = emphasize,
        onClick = { onPress(key.action) {} },
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight(),
    )
}

@Composable
private fun QuickKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    emphasize: Boolean = false,
) {
    val background = if (emphasize) Color.White.copy(alpha = 0.16f) else KeyColor
    Column(
        modifier = modifier
            .clip(KeyShape)
            .background(background)
            .border(1.dp, KeyBorder, KeyShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = if (caption == null && label.length <= 4) 20.sp else 17.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 22.sp,
        )
        if (caption != null) {
            Text(
                text = caption,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun QuickBusyOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
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

/** 機器操作のテレビ電源と同じく、押すたびに入と切を交互に送る。 */
private fun tvPowerAction(turnOn: Boolean): ControlAction {
    val power = controlTarget("テレビ").key("power").action as ControlAction.Device
    return power.copy(command = if (turnOn) "turnOn" else "turnOff")
}

private fun controlTarget(title: String): ControlTarget =
    ControlSections.firstNotNullOf { section ->
        section.targets.firstOrNull { it.title == title }
    }
