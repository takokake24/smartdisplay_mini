package app.smartdisplay

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.smartdisplay.audio.SoundPresenceGate
import app.smartdisplay.audio.SoundTestPage
import app.smartdisplay.battery.BatteryAnnounceMode
import app.smartdisplay.battery.BatteryAnnounceSettings
import app.smartdisplay.calendar.CalendarDaySections
import app.smartdisplay.calendar.CalendarEvent
import app.smartdisplay.calendar.CalendarRepository
import app.smartdisplay.calendar.DeviceCalendar
import app.smartdisplay.calendar.displayTitle
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.jihou.JihouSettings
import app.smartdisplay.jihou.JihouSpecialIntros
import app.smartdisplay.media.MusicContent
import app.smartdisplay.notification.NotificationAnnounceSettings
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.observance.ObservanceRows
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.switchbot.DeviceControlContent
import app.smartdisplay.switchbot.QuickActionsEntry
import app.smartdisplay.ui.Panel
import app.smartdisplay.util.NetworkLog
import app.smartdisplay.util.NetworkLogCategory
import app.smartdisplay.util.NetworkLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val VolumeChangedAction = "android.media.VOLUME_CHANGED_ACTION"
private const val ExtraVolumeStreamType = "android.media.EXTRA_VOLUME_STREAM_TYPE"

private enum class MenuPage {
    Home,
    Settings,
    NotificationSettings,
    JihouSettings,
    TestExecution,
    JihouTest,
    Music,
    DeviceControl,
    Calendar,
    Observances,
    NetworkLog,
    SoundTest,
}

@Composable
fun MenuScreen(
    now: LocalDateTime,
    jihouSettings: JihouSettings,
    notificationAnnounceSettings: NotificationAnnounceSettings,
    batteryAnnounceSettings: BatteryAnnounceSettings,
    soundPresenceGate: SoundPresenceGate,
    chimeController: HourlyChimeController,
    announcementGate: AnnouncementGate,
    notifications: NotificationCenter,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onSoundTestOpen: (Boolean) -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    startOnMedia: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(if (startOnMedia) MenuPage.Music else MenuPage.Home) }
    val reportSoundTestOpen by rememberUpdatedState(onSoundTestOpen)
    DisposableEffect(page) {
        val open = page == MenuPage.SoundTest
        if (open) {
            reportSoundTestOpen(true)
        }
        onDispose {
            if (open) {
                reportSoundTestOpen(false)
            }
        }
    }
    val latestInteraction by rememberUpdatedState(onInteraction)
    val context = LocalContext.current
    val calendarRepository = remember { CalendarRepository(context.applicationContext) }
    var hasCalendarPermission by remember {
        mutableStateOf(calendarRepository.hasPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCalendarPermission = granted
        page = MenuPage.Calendar
    }
    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        hasCalendarPermission = calendarRepository.hasPermission()
    }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
        appSettingsLauncher.launch(intent)
    }

    fun openCalendar() {
        if (hasCalendarPermission) {
            page = MenuPage.Calendar
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    fun navigateBack() {
        when (page) {
            MenuPage.Home -> onBack()
            MenuPage.Settings,
            MenuPage.Music,
            MenuPage.DeviceControl,
            MenuPage.Calendar,
            MenuPage.Observances,
            -> page = MenuPage.Home
            MenuPage.NotificationSettings,
            MenuPage.JihouSettings,
            MenuPage.TestExecution,
            MenuPage.NetworkLog,
            MenuPage.SoundTest,
            -> page = MenuPage.Settings
            MenuPage.JihouTest -> page = MenuPage.TestExecution
        }
    }

    BackHandler {
        onTapSound()
        navigateBack()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            latestInteraction()
                        }
                    }
                }
            }
            .padding(40.dp),
    ) {
        MenuTopBar(
            now = now,
            onBack = {
                onTapSound()
                navigateBack()
            },
            onOpenSettings = if (page == MenuPage.Home) {
                {
                    onTapSound()
                    page = MenuPage.Settings
                }
            } else {
                null
            },
        )

        when (page) {
            MenuPage.Home -> MenuHomeContent(
                onOpenMusic = {
                    onTapSound()
                    page = MenuPage.Music
                },
                onOpenDeviceControl = {
                    onTapSound()
                    page = MenuPage.DeviceControl
                },
                onOpenCalendar = {
                    onTapSound()
                    openCalendar()
                },
                onOpenObservances = {
                    onTapSound()
                    page = MenuPage.Observances
                },
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Observances -> MenuSubpageColumn(
                title = "行事",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                ObservanceRows(
                    today = now.toLocalDate(),
                    onTapSound = onTapSound,
                )
            }
            MenuPage.Settings -> SettingsContent(
                notificationAnnounceSettings = notificationAnnounceSettings,
                batteryAnnounceSettings = batteryAnnounceSettings,
                jihouSettings = jihouSettings,
                onOpenNotificationSettings = {
                    onTapSound()
                    page = MenuPage.NotificationSettings
                },
                onOpenJihouSettings = {
                    onTapSound()
                    page = MenuPage.JihouSettings
                },
                onOpenTestExecution = {
                    onTapSound()
                    page = MenuPage.TestExecution
                },
                onOpenNetworkLog = {
                    onTapSound()
                    page = MenuPage.NetworkLog
                },
                onOpenSoundTest = {
                    onTapSound()
                    page = MenuPage.SoundTest
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.NetworkLog -> NetworkLogContent(
                now = now,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.SoundTest -> SoundTestPage(
                notifications = notifications,
                chimeController = chimeController,
                announcementGate = announcementGate,
                presenceGate = soundPresenceGate,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.NotificationSettings -> NotificationSettingsContent(
                settings = notificationAnnounceSettings,
                batteryAnnounceSettings = batteryAnnounceSettings,
                soundPresenceGate = soundPresenceGate,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.JihouSettings -> JihouSettingsContent(
                settings = jihouSettings,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.TestExecution -> TestExecutionContent(
                onOpenJihouTest = {
                    onTapSound()
                    page = MenuPage.JihouTest
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.JihouTest -> JihouTestContent(
                chimeController = chimeController,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Music -> MusicContent(
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.DeviceControl -> DeviceControlContent(
                now = now,
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Calendar -> CalendarContent(
                now = now,
                repository = calendarRepository,
                hasPermission = hasCalendarPermission,
                onOpenAppSettings = {
                    onTapSound()
                    openAppSettings()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun MenuTopBar(
    now: LocalDateTime,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Panel(
            onClick = onBack,
            modifier = Modifier.wrapContentWidth(Alignment.Start),
        ) {
            Text(
                text = "戻る",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onOpenSettings != null) {
                Panel(onClick = onOpenSettings) {
                    Text(
                        text = "設定",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MenuClock(now = now)
                MenuBatteryStatus()
            }
        }
    }
}

@Composable
private fun MenuClock(now: LocalDateTime) {
    val locale = Locale.getDefault()
    val timeFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    val timeStyle = remember {
        TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
            fontFeatureSettings = "tnum",
        )
    }
    val secondStyle = remember {
        TextStyle(
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            fontFeatureSettings = "tnum",
        )
    }
    val widthProbe = remember(locale, timeFormatter) {
        listOf(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2026, 1, 1, 11, 59),
            LocalDateTime.of(2026, 1, 1, 12, 0),
            LocalDateTime.of(2026, 1, 1, 23, 59),
        ).maxBy { it.format(timeFormatter).length }
            .format(timeFormatter)
    }
    Box(contentAlignment = Alignment.CenterEnd) {
        Row(
            modifier = Modifier.alpha(0f),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = widthProbe, style = timeStyle)
            Text(text = "88", style = secondStyle)
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = now.format(timeFormatter),
                color = Color.White.copy(alpha = 0.72f),
                style = timeStyle,
            )
            Text(
                text = now.second.toString().padStart(2, '0'),
                color = Color.White.copy(alpha = 0.4f),
                style = secondStyle,
            )
        }
    }
}

private data class MenuBatteryState(
    val percent: Int,
    val charging: Boolean,
)

@Composable
private fun rememberMenuBatteryState(): MenuBatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(MenuBatteryState(percent = 100, charging = false)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                state = readMenuBatteryState(intent)
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        state = readMenuBatteryState(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

private fun readMenuBatteryState(intent: Intent?): MenuBatteryState {
    if (intent == null) {
        return MenuBatteryState(percent = 100, charging = false)
    }
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    val percent = if (level < 0) {
        100
    } else {
        ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    }
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        plugged != 0
    return MenuBatteryState(percent = percent, charging = charging)
}

@Composable
private fun MenuBatteryStatus() {
    val battery = rememberMenuBatteryState()
    val low = battery.percent < 10 && !battery.charging
    val color = if (low) {
        Color(0xFFE53935).copy(alpha = 0.9f)
    } else {
        Color.White.copy(alpha = 0.55f)
    }
    val percentStyle = remember {
        TextStyle(
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            fontFeatureSettings = "tnum",
            textAlign = TextAlign.End,
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BatteryStatusIcon(
            percent = battery.percent,
            charging = battery.charging,
            color = color,
            modifier = Modifier.size(width = 22.dp, height = 14.dp),
        )
        Box(contentAlignment = Alignment.CenterEnd) {
            Text(
                text = "100%",
                style = percentStyle,
                modifier = Modifier.alpha(0f),
            )
            Text(
                text = "${battery.percent}%",
                color = color,
                style = percentStyle,
            )
        }
    }
}

@Composable
private fun BatteryStatusIcon(
    percent: Int,
    charging: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.12f)
        val bodyWidth = size.width * 0.82f
        val bodyHeight = size.height * 0.86f
        val bodyTop = (size.height - bodyHeight) / 2f
        val corner = CornerRadius(bodyHeight * 0.22f, bodyHeight * 0.22f)
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, bodyTop),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = corner,
            style = stroke,
        )
        val tipWidth = size.width * 0.1f
        val tipHeight = bodyHeight * 0.42f
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyWidth + size.width * 0.02f, (size.height - tipHeight) / 2f),
            size = Size(tipWidth, tipHeight),
            cornerRadius = CornerRadius(tipWidth * 0.35f, tipWidth * 0.35f),
        )
        val inset = stroke.width * 1.6f
        val fillMaxWidth = (bodyWidth - inset * 2f).coerceAtLeast(0f)
        val fillWidth = fillMaxWidth * (percent.coerceIn(0, 100) / 100f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = color.copy(alpha = if (charging) color.alpha * 0.45f else color.alpha),
                topLeft = Offset(inset, bodyTop + inset),
                size = Size(fillWidth, (bodyHeight - inset * 2f).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(bodyHeight * 0.12f, bodyHeight * 0.12f),
            )
        }
        if (charging) {
            val bolt = Path().apply {
                val cx = bodyWidth * 0.5f
                val cy = size.height * 0.5f
                moveTo(cx + bodyWidth * 0.04f, cy - bodyHeight * 0.34f)
                lineTo(cx - bodyWidth * 0.14f, cy + bodyHeight * 0.04f)
                lineTo(cx + bodyWidth * 0.02f, cy + bodyHeight * 0.04f)
                lineTo(cx - bodyWidth * 0.04f, cy + bodyHeight * 0.34f)
                lineTo(cx + bodyWidth * 0.14f, cy - bodyHeight * 0.04f)
                lineTo(cx - bodyWidth * 0.02f, cy - bodyHeight * 0.04f)
                close()
            }
            drawPath(path = bolt, color = color)
        }
    }
}

@Composable
private fun MenuHomeContent(
    onOpenMusic: () -> Unit,
    onOpenDeviceControl: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenObservances: () -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menuItems = listOf(
        "メディア" to onOpenMusic,
        "機器操作" to onOpenDeviceControl,
        "カレンダー" to onOpenCalendar,
        "行事" to onOpenObservances,
    )
    val columns = 2
    val rowGap = 16.dp
    val minRowHeight = 96.dp
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val rows = menuItems.chunked(columns)
            val rowCount = rows.size.coerceAtLeast(1)
            val gaps = rowGap * (rowCount - 1).coerceAtLeast(0)
            val availableForRows = maxHeight - gaps
            val fittedRowHeight = availableForRows / rowCount
            // 余白を残してパネルはだいたい 2/3 の高さにする
            val targetRowHeight = fittedRowHeight * 2f / 3f
            val rowHeight = if (targetRowHeight < minRowHeight) minRowHeight else targetRowHeight
            val scrollable = targetRowHeight < minRowHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (scrollable) {
                            Modifier
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(rowGap),
            ) {
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(rowHeight),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        rowItems.forEach { (title, onClick) ->
                            HomeMenuTile(
                                title = title,
                                onClick = onClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        repeat(columns - rowItems.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolumePanel(
                onTapSound = onTapSound,
                modifier = Modifier.weight(1f),
            )
            QuickActionsEntry(
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
            )
        }
    }
}

@Composable
private fun HomeMenuTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun SettingsContent(
    notificationAnnounceSettings: NotificationAnnounceSettings,
    batteryAnnounceSettings: BatteryAnnounceSettings,
    jihouSettings: JihouSettings,
    onOpenNotificationSettings: () -> Unit,
    onOpenJihouSettings: () -> Unit,
    onOpenTestExecution: () -> Unit,
    onOpenNetworkLog: () -> Unit,
    onOpenSoundTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speech = if (notificationAnnounceSettings.speechEnabled) "読み上げオン" else "読み上げオフ"
    val silent = if (notificationAnnounceSettings.includeSilent) "・サイレントも" else ""
    val battery = when (batteryAnnounceSettings.mode) {
        BatteryAnnounceMode.Always -> "・電池は常に"
        BatteryAnnounceMode.Resident -> "・電池は常駐時"
        BatteryAnnounceMode.Off -> "・電池オフ"
    }
    val notificationSubtitle = "$speech$silent$battery"
    MenuSubpageColumn(title = "設定", modifier = modifier) {
        SettingsItem(
            title = "通知設定",
            subtitle = notificationSubtitle,
            onClick = onOpenNotificationSettings,
        )
        SettingsItem(
            title = "時報設定",
            subtitle = if (jihouSettings.enabled) "オン" else "オフ",
            onClick = onOpenJihouSettings,
        )
        SettingsItem(
            title = "テスト実行",
            onClick = onOpenTestExecution,
        )
        SettingsItem(
            title = "通信ログ",
            onClick = onOpenNetworkLog,
        )
        SettingsItem(
            title = "サウンドテスト",
            onClick = onOpenSoundTest,
        )
    }
}

@Composable
private fun NetworkLogContent(
    now: LocalDateTime,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries by NetworkLog.entries.collectAsState()
    var categoryFilter by remember { mutableStateOf<NetworkLogCategory?>(null) }
    val filtered = remember(entries, categoryFilter) {
        if (categoryFilter == null) {
            entries
        } else {
            entries.filter { it.category == categoryFilter }
        }
    }
    val filteredState = rememberUpdatedState(filtered)
    val listState = rememberLazyListState()
    // ユーザーが一番上にいるときだけ最新追従。それ以外は見ていた行を固定
    var followLatest by remember { mutableStateOf(true) }
    var pinnedItemId by remember { mutableStateOf<Long?>(null) }
    var pinnedItemOffset by remember { mutableIntStateOf(0) }
    var ignoreScrollTracking by remember { mutableStateOf(false) }
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("M/d H:mm:ss", Locale.JAPANESE)
    }
    LaunchedEffect(now) {
        NetworkLog.prune(now)
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (ignoreScrollTracking) {
                    return@collect
                }
                val items = filteredState.value
                if (index == 0 && offset == 0) {
                    followLatest = true
                    pinnedItemId = null
                    pinnedItemOffset = 0
                } else {
                    followLatest = false
                    items.getOrNull(index)?.id?.let { id ->
                        pinnedItemId = id
                        pinnedItemOffset = offset
                    }
                }
            }
    }
    LaunchedEffect(filtered) {
        val items = filtered
        if (items.isEmpty()) {
            return@LaunchedEffect
        }
        if (listState.isScrollInProgress) {
            snapshotFlow { listState.isScrollInProgress }.first { !it }
        }
        ignoreScrollTracking = true
        try {
            if (followLatest) {
                listState.scrollToItem(0)
            } else {
                val pin = pinnedItemId ?: return@LaunchedEffect
                val index = items.indexOfFirst { it.id == pin }
                if (index >= 0) {
                    listState.scrollToItem(index, pinnedItemOffset)
                }
            }
        } finally {
            ignoreScrollTracking = false
        }
    }
    Column(
        modifier = modifier
            .padding(top = 28.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "通信ログ",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "直近3時間の外部通信。古い記録は自動削除されます。",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 16.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NetworkLogFilterChip(
                label = "すべて",
                selected = categoryFilter == null,
                onClick = {
                    onTapSound()
                    categoryFilter = null
                },
            )
            NetworkLogCategory.entries.forEach { category ->
                NetworkLogFilterChip(
                    label = category.label,
                    selected = categoryFilter == category,
                    onClick = {
                        onTapSound()
                        categoryFilter = category
                    },
                )
            }
        }
        if (filtered.isEmpty()) {
            Text(
                text = if (entries.isEmpty()) {
                    "通信履歴はまだありません"
                } else {
                    "この分類の通信履歴はありません"
                },
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    items = filtered,
                    key = { it.id },
                ) { entry ->
                    NetworkLogRow(
                        entry = entry,
                        timeFormatter = timeFormatter,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkLogFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
        fontSize = 18.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (selected) Color.White else Color.White.copy(alpha = 0.12f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun NetworkLogRow(
    entry: NetworkLogEntry,
    timeFormatter: DateTimeFormatter,
) {
    val resultColor = when {
        entry.result.startsWith("成功") -> Color(0xFF81C784)
        entry.result.startsWith("失敗") -> Color(0xFFE57373)
        else -> Color.White.copy(alpha = 0.75f)
    }
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.category.label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 15.sp,
        )
        Text(
            text = entry.destination,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "開始 ${entry.startedAt.format(timeFormatter)}",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "終了 ${entry.endedAt.format(timeFormatter)}",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = entry.result,
            color = resultColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TestExecutionContent(
    onOpenJihouTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "テスト実行", modifier = modifier) {
        SettingsItem(
            title = "時報テスト",
            subtitle = "四半期・特殊正時",
            onClick = onOpenJihouTest,
        )
    }
}

@Composable
private fun JihouTestContent(
    chimeController: HourlyChimeController,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "時報テスト", modifier = modifier) {
        Text(
            text = "再生",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
        )
        SettingsItem(
            title = "ランダム再生",
            subtitle = "四半期メロディをランダムに1本",
            onClick = {
                onTapSound()
                chimeController.playTestRandom()
            },
        )
        listOf(0, 15, 30, 45).forEach { minute ->
            val label = if (minute == 0) "毎時0分" else "毎時${minute}分"
            SettingsItem(
                title = label,
                subtitle = "メロディと時刻読み上げ",
                onClick = {
                    onTapSound()
                    chimeController.playTestQuarter(minute)
                },
            )
        }
        Text(
            text = "特殊時刻",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        JihouSpecialIntros.forEach { intro ->
            SettingsItem(
                title = intro.timeLabel,
                subtitle = "イントロ → 時報メロディ → 読み上げ",
                onClick = {
                    onTapSound()
                    chimeController.playTestSpecial(
                        hour = intro.hour,
                        minute = intro.minute,
                    )
                },
            )
        }
    }
}

@Composable
private fun CalendarContent(
    now: LocalDateTime,
    repository: CalendarRepository,
    hasPermission: Boolean,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sections by remember { mutableStateOf<CalendarDaySections?>(null) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    LaunchedEffect(hasPermission, now.toLocalDate()) {
        if (!hasPermission) {
            sections = null
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            val all = repository.loadCalendars()
            all to repository.loadMenuSections(all.map { it.id }.toSet(), now)
        }
        calendars = loaded.first
        sections = loaded.second
    }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("H:mm") }
    val weekDateFormatter = remember {
        DateTimeFormatter.ofPattern("M月d日（E）", Locale.JAPANESE)
    }
    MenuSubpageColumn(title = "カレンダー", modifier = modifier) {
        if (!hasPermission) {
            Text(
                text = "カレンダーの権限が許可されていないため、予定を表示できません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 18.sp,
            )
            SettingsItem(
                title = "アプリの設定を開く",
                subtitle = "端末のこのアプリの設定画面で権限を変更できます",
                onClick = onOpenAppSettings,
            )
            return@MenuSubpageColumn
        }
        if (calendars.isEmpty()) {
            Text(
                text = "表示できるカレンダーがありません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 18.sp,
            )
            return@MenuSubpageColumn
        }
        val data = sections
        CalendarSection(
            title = "今日このあと",
            emptyLabel = "このあとの予定はありません",
            events = data?.upcomingToday.orEmpty(),
            timeFormatter = timeFormatter,
            showDate = false,
        )
        CalendarSection(
            title = "明日",
            emptyLabel = "明日の予定はありません",
            events = data?.tomorrow.orEmpty(),
            timeFormatter = timeFormatter,
            showDate = false,
            modifier = Modifier.padding(top = 8.dp),
        )
        CalendarSection(
            title = "一週間以内",
            emptyLabel = "一週間以内の予定はありません",
            events = data?.weekAhead.orEmpty(),
            timeFormatter = timeFormatter,
            dateFormatter = weekDateFormatter,
            showDate = true,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CalendarSection(
    title: String,
    emptyLabel: String,
    events: List<CalendarEvent>,
    timeFormatter: DateTimeFormatter,
    showDate: Boolean,
    modifier: Modifier = Modifier,
    dateFormatter: DateTimeFormatter? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
        )
        if (events.isEmpty()) {
            Text(
                text = emptyLabel,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 18.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                events.forEach { event ->
                    CalendarEventRow(
                        event = event,
                        timeFormatter = timeFormatter,
                        dateFormatter = dateFormatter,
                        showDate = showDate,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarEventRow(
    event: CalendarEvent,
    timeFormatter: DateTimeFormatter,
    dateFormatter: DateTimeFormatter? = null,
    showDate: Boolean,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.displayTitle(),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val whenText = buildString {
                    if (showDate && dateFormatter != null) {
                        append(event.start.toLocalDate().format(dateFormatter))
                        append("  ")
                    }
                    if (event.allDay) {
                        append("終日")
                    } else {
                        append(event.start.format(timeFormatter))
                        append(" – ")
                        append(event.end.format(timeFormatter))
                    }
                }
                Text(
                    text = whenText,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NotificationSettingsContent(
    settings: NotificationAnnounceSettings,
    batteryAnnounceSettings: BatteryAnnounceSettings,
    soundPresenceGate: SoundPresenceGate,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "通知設定", modifier = modifier) {
        Text(
            text = "他アプリの通知を読み上げます",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        SettingsToggle(
            title = "読み上げ",
            subtitle = if (settings.speechEnabled) "オン" else "オフ",
            checked = settings.speechEnabled,
            onCheckedChange = { enabled ->
                onTapSound()
                settings.updateSpeechEnabled(enabled)
            },
        )
        SettingsToggle(
            title = "サイレント通知も読み上げ",
            subtitle = when {
                !settings.speechEnabled -> "読み上げがオンのときに選べます"
                settings.includeSilent -> "オン"
                else -> "オフ"
            },
            checked = settings.includeSilent,
            enabled = settings.speechEnabled,
            onCheckedChange = { include ->
                onTapSound()
                settings.updateIncludeSilent(include)
            },
        )
        SettingsToggle(
            title = "外出中も鳴らす",
            subtitle = if (soundPresenceGate.awaySoundEnabled) {
                "オン（Wi-Fi・メディア・Bluetooth接続で自動オフ）"
            } else {
                "オフ"
            },
            checked = soundPresenceGate.awaySoundEnabled,
            onCheckedChange = { enabled ->
                onTapSound()
                soundPresenceGate.setAwaySoundEnabled(enabled)
            },
        )
        Text(
            text = "電池残量読み上げ",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
        SettingsItem(
            title = "常に",
            subtitle = "起動中・常駐時どちらでも読み上げる",
            selected = batteryAnnounceSettings.mode == BatteryAnnounceMode.Always,
            onClick = {
                onTapSound()
                batteryAnnounceSettings.updateMode(BatteryAnnounceMode.Always)
            },
        )
        SettingsItem(
            title = "常駐時",
            subtitle = "アプリが画面に出ていないときだけ読み上げる",
            selected = batteryAnnounceSettings.mode == BatteryAnnounceMode.Resident,
            onClick = {
                onTapSound()
                batteryAnnounceSettings.updateMode(BatteryAnnounceMode.Resident)
            },
        )
        SettingsItem(
            title = "オフ",
            selected = batteryAnnounceSettings.mode == BatteryAnnounceMode.Off,
            onClick = {
                onTapSound()
                batteryAnnounceSettings.updateMode(BatteryAnnounceMode.Off)
            },
        )
    }
}

@Composable
private fun JihouSettingsContent(
    settings: JihouSettings,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "時報設定", modifier = modifier) {
        SettingsToggle(
            title = "時報",
            subtitle = if (settings.enabled) "オン" else "オフ",
            checked = settings.enabled,
            onCheckedChange = { enabled ->
                onTapSound()
                settings.updateEnabled(enabled)
            },
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 18.sp,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF3DDC84),
                    checkedBorderColor = Color(0xFF3DDC84),
                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.28f),
                ),
            )
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    selected: Boolean = false,
) {
    Panel(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (selected) {
                SettingsCheckMark(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun SettingsCheckMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(28.dp)) {
        val strokeWidth = 3.dp.toPx()
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.16f, size.height * 0.52f),
            end = Offset(size.width * 0.40f, size.height * 0.76f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width * 0.40f, size.height * 0.76f),
            end = Offset(size.width * 0.84f, size.height * 0.28f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun VolumePanel(
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val audio = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }
    if (audio != null) {
        VolumeControls(
            context = context,
            audio = audio,
            onTapSound = onTapSound,
            modifier = modifier,
        )
    }
}

@Composable
private fun VolumeControls(
    context: Context,
    audio: AudioManager,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (initialLevel, maxLevel) = remember(audio) { musicVolume(audio) }
    var level by remember(audio) { mutableFloatStateOf(initialLevel.toFloat()) }
    var muted by remember(audio) { mutableStateOf(audio.isStreamMute(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val stream = intent?.getIntExtra(ExtraVolumeStreamType, -1) ?: -1
                if (stream != AudioManager.STREAM_MUSIC) {
                    return
                }
                level = musicVolume(audio).first.toFloat()
                muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VolumeChangedAction),
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    val displayPercent = if (muted) {
        0
    } else {
        (level * 100f / maxLevel).roundToInt()
    }
    ControlPanel(
        title = "音量",
        valueLabel = "$displayPercent%",
        value = if (muted) 0f else level,
        onValueChange = { raw ->
            val next = raw.roundToInt().coerceIn(0, maxLevel)
            level = next.toFloat()
            setMusicVolume(audio, next)
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || next == 0
        },
        onValueChangeFinished = onTapSound,
        onDecrease = {
            onTapSound()
            val next = (level.roundToInt() - 1).coerceIn(0, maxLevel)
            level = next.toFloat()
            setMusicVolume(audio, next)
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || next == 0
        },
        onIncrease = {
            onTapSound()
            val next = (level.roundToInt() + 1).coerceIn(0, maxLevel)
            level = next.toFloat()
            setMusicVolume(audio, next)
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        },
        trailingAction = {
            MuteButton(
                muted = muted,
                onClick = {
                    if (muted) {
                        toggleMute(audio)
                        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                        level = musicVolume(audio).first.toFloat()
                        onTapSound()
                    } else {
                        onTapSound()
                        toggleMute(audio)
                        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
                        level = musicVolume(audio).first.toFloat()
                    }
                },
            )
        },
        valueRange = 0f..maxLevel.toFloat(),
        steps = (maxLevel - 1).coerceAtLeast(0),
        modifier = modifier,
    )
}

@Composable
private fun ControlPanel(
    title: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    Panel(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = valueLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 20.sp,
                )
                trailingAction?.invoke()
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StepButton(label = "−", onClick = onDecrease)
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.92f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
                modifier = Modifier.weight(1f),
            )
            StepButton(label = "＋", onClick = onIncrease)
        }
    }
}

@Composable
private fun MenuSubpageColumn(
    title: String,
    modifier: Modifier = Modifier,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (headerAction == null) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                )
                headerAction()
            }
        }
        content()
    }
}

@Composable
private fun StepButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MuteButton(
    muted: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = if (muted) "解除" else "ミュート",
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (muted) {
                    Color.White.copy(alpha = 0.22f)
                } else {
                    Color.White.copy(alpha = 0.12f)
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
    )
}

private fun musicVolume(audio: AudioManager): Pair<Int, Int> {
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
    return current to max
}

private fun setMusicVolume(audio: AudioManager, level: Int) {
    try {
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) && level > 0) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
    } catch (_: SecurityException) {
        return
    }
}

private fun toggleMute(audio: AudioManager) {
    try {
        val direction = if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
            AudioManager.ADJUST_UNMUTE
        } else {
            AudioManager.ADJUST_MUTE
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    } catch (_: SecurityException) {
        return
    }
}
