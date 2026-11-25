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
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.smartdisplay.away.AwayAlertSettings
import app.smartdisplay.calendar.CalendarAnnounceKind
import app.smartdisplay.calendar.CalendarDaySections
import app.smartdisplay.calendar.CalendarEvent
import app.smartdisplay.calendar.CalendarRepository
import app.smartdisplay.calendar.CalendarSettings
import app.smartdisplay.calendar.DeviceCalendar
import app.smartdisplay.calendar.displayTitle
import app.smartdisplay.earthquake.EarthquakeSettings
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.jihou.JihouSeason
import app.smartdisplay.jihou.JihouSettings
import app.smartdisplay.jihou.displayName
import app.smartdisplay.jihou.specialIntros
import app.smartdisplay.audio.SoundTestPage
import app.smartdisplay.away.AwayAlertArchive
import app.smartdisplay.away.AwayAlertController
import app.smartdisplay.away.AwayAlertListPage
import app.smartdisplay.away.AwayAlertMenuLock
import app.smartdisplay.away.AwayAlertTimelinePage
import app.smartdisplay.notification.AppNotification
import app.smartdisplay.notification.NotificationAnnounceSettings
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationHistoryPanel
import app.smartdisplay.notification.OutlinedStatusMark
import app.smartdisplay.bus.BusReminderScheduler
import app.smartdisplay.expiration.ExpirationContent
import app.smartdisplay.garbage.GarbageDayRows
import app.smartdisplay.observance.ObservanceRows
import app.smartdisplay.memorial.MemorialDayContent
import app.smartdisplay.memorial.MemorialRepository
import app.smartdisplay.night.NightModeMaxBrightness
import app.smartdisplay.night.NightModeMinBrightness
import app.smartdisplay.night.NightModeSettings
import app.smartdisplay.night.canWriteSystemBrightness
import app.smartdisplay.night.systemBrightnessSettingsIntent
import app.smartdisplay.bus.BusRemindersContent
import app.smartdisplay.bus.BusTimetableContent
import app.smartdisplay.bus.BusTimetableRepository
import app.smartdisplay.bus.BusTrip
import app.smartdisplay.train.TrainTimetableContent
import app.smartdisplay.train.TrainTimetableRepository
import app.smartdisplay.transit.LineOperation
import app.smartdisplay.transit.TrainLine
import app.smartdisplay.transit.toStatusMark
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.timer.TimerController
import app.smartdisplay.timer.AlarmPage
import app.smartdisplay.timer.ClockHubPage
import app.smartdisplay.timer.TimerPage
import app.smartdisplay.steps.StepsAccess
import app.smartdisplay.steps.StepsPage
import app.smartdisplay.speech.SpeechSpeaker
import app.smartdisplay.spotify.MusicContent
import app.smartdisplay.switchbot.DeviceControlContent
import app.smartdisplay.switchbot.QuickActionsEntry
import app.smartdisplay.ui.Panel
import app.smartdisplay.ui.RefreshChip
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NetworkLog
import app.smartdisplay.util.NetworkLogCategory
import app.smartdisplay.util.NetworkLogEntry
import app.smartdisplay.util.formatLastUpdatedLabel
import app.smartdisplay.warning.WeatherWarningItem
import app.smartdisplay.warning.WeatherWarningSeverity
import app.smartdisplay.weather.DailyWeather
import app.smartdisplay.weather.WeatherAnnounceKind
import app.smartdisplay.weather.WeatherRepository
import app.smartdisplay.weather.WeatherIcon
import app.smartdisplay.weather.daylightRemainingLabel
import app.smartdisplay.weather.moonPhaseInfo
import app.smartdisplay.weather.weatherDescriptionJa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
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
    JihouTodayTheme,
    CalendarSettings,
    VolumeBrightness,
    EarthquakeSettings,
    AwayAlertVolume,
    TestExecution,
    JihouTest,
    EarthquakeTest,
    WeatherCalendarTest,
    WeatherWarningTest,
    Music,
    DeviceControl,
    Weather,
    WeatherWarning,
    Calendar,
    Observances,
    Expiration,
    Steps,
    AwayAlerts,
    AwayAlertPhotos,
    TransitInfo,
    TrainTimetable,
    BusTimetable,
    BusReminders,
    MemorialDays,
    GarbageDays,
    Clock,
    Timer,
    Alarm,
    NotificationHistory,
    NetworkLog,
    SoundTest,
    NightMode,
}

@Composable
fun MenuScreen(
    now: LocalDateTime,
    jihouSettings: JihouSettings,
    notificationAnnounceSettings: NotificationAnnounceSettings,
    nightModeSettings: NightModeSettings,
    calendarSettings: CalendarSettings,
    earthquakeSettings: EarthquakeSettings,
    awayAlertSettings: AwayAlertSettings,
    awayAlerts: AwayAlertArchive,
    awayAlertController: AwayAlertController,
    chimeController: HourlyChimeController,
    announcementGate: AnnouncementGate,
    notifications: NotificationCenter,
    history: List<AppNotification>,
    operations: Map<TrainLine, LineOperation>,
    weatherRepository: WeatherRepository,
    weatherLastFetchedAt: LocalDateTime?,
    earthquakeLastPolledAt: LocalDateTime?,
    transitLastFetchedAt: LocalDateTime?,
    activeWeatherWarnings: List<WeatherWarningItem>,
    weatherWarningLastFetchedAt: LocalDateTime?,
    trainTimetableRepository: TrainTimetableRepository,
    busTimetableRepository: BusTimetableRepository,
    memorialRepository: MemorialRepository,
    busReminderScheduler: BusReminderScheduler,
    busScheduledTrips: List<BusTrip>,
    onRefreshTransit: () -> Unit,
    onScheduleSplitTest: () -> Unit,
    onCalendarAnnounceTest: (CalendarAnnounceKind) -> Unit,
    onCalendarSoonTest: () -> Unit,
    onWeatherAnnounceTest: (WeatherAnnounceKind) -> Unit,
    onStepsAnnounceTest: () -> Unit,
    onMemorialAnnounceTest: () -> Unit,
    onQuoteAnnounceTest: () -> Unit,
    onExpirationAnnounceTest: () -> Unit,
    onNoonNoticeTest: () -> Unit,
    onLateNoticeTest: () -> Unit,
    onSunsetTest: () -> Unit,
    onMorningBriefTest: () -> Unit,
    onEewTest: () -> Unit,
    onEarthquakeIntensityTest: (Int?) -> Unit,
    onEarthquakeIntensityUpdateTest: () -> Unit,
    onWeatherWarningTest: (WeatherWarningSeverity) -> Unit,
    onWeatherWarningCancelTest: (WeatherWarningSeverity) -> Unit,
    onPreviewEewVolume: (Float) -> Unit,
    onPreviewAwayVolume: (Float) -> Unit,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onSoundTestOpen: (Boolean) -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    startOnMedia: Boolean = false,
    timer: TimerController,
    onTimerStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(if (startOnMedia) MenuPage.Music else MenuPage.Home) }
    val timerState by timer.state.collectAsState()
    val alarmState by timer.alarmState.collectAsState()
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
    var selectedAwayAlertId by remember { mutableStateOf<String?>(null) }
    var awayAlertsUnlocked by remember { mutableStateOf(false) }
    val latestInteraction by rememberUpdatedState(onInteraction)
    val unreadCount = history.count { !it.read }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val calendarRepository = remember { CalendarRepository(context.applicationContext) }
    var pendingStepsTest by remember { mutableStateOf(false) }
    val stepsPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        val runTest = pendingStepsTest
        pendingStepsTest = false
        if (runTest && StepsAccess.readPermission in granted) {
            onStepsAnnounceTest()
        }
    }
    var hasCalendarPermission by remember {
        mutableStateOf(calendarRepository.hasPermission())
    }
    var pendingCalendarPage by remember { mutableStateOf<MenuPage?>(null) }
    var pendingCalendarTest by remember { mutableStateOf<CalendarAnnounceKind?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCalendarPermission = granted
        val target = pendingCalendarPage
        val testKind = pendingCalendarTest
        pendingCalendarPage = null
        pendingCalendarTest = null
        if (!granted) {
            if (testKind != null) {
                onCalendarAnnounceTest(testKind)
            }
            return@rememberLauncherForActivityResult
        }
        if (target != null) {
            page = target
        }
        if (testKind != null) {
            onCalendarAnnounceTest(testKind)
        }
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

    fun openWithCalendarPermission(target: MenuPage) {
        if (hasCalendarPermission) {
            page = target
        } else {
            pendingCalendarPage = target
            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    fun navigateBack() {
        when (page) {
            MenuPage.Home -> onBack()
            MenuPage.Settings,
            MenuPage.TransitInfo,
            MenuPage.TrainTimetable,
            MenuPage.BusTimetable,
            MenuPage.Music,
            MenuPage.Clock,
            MenuPage.DeviceControl,
            MenuPage.Weather,
            MenuPage.WeatherWarning,
            MenuPage.Calendar,
            MenuPage.Observances,
            MenuPage.Expiration,
            MenuPage.Steps,
            MenuPage.AwayAlerts,
            MenuPage.MemorialDays,
            MenuPage.GarbageDays,
            MenuPage.NotificationHistory,
            -> page = MenuPage.Home
            MenuPage.AwayAlertPhotos -> page = MenuPage.AwayAlerts
            MenuPage.BusReminders -> page = MenuPage.BusTimetable
            MenuPage.Timer,
            MenuPage.Alarm,
            -> page = MenuPage.Clock
            MenuPage.NotificationSettings,
            MenuPage.JihouSettings,
            MenuPage.CalendarSettings,
            MenuPage.VolumeBrightness,
            MenuPage.TestExecution,
            MenuPage.NetworkLog,
            MenuPage.SoundTest,
            -> page = MenuPage.Settings
            MenuPage.NightMode,
            MenuPage.EarthquakeSettings,
            MenuPage.AwayAlertVolume,
            -> page = MenuPage.VolumeBrightness
            MenuPage.JihouTodayTheme -> page = MenuPage.JihouSettings
            MenuPage.JihouTest,
            MenuPage.EarthquakeTest,
            MenuPage.WeatherCalendarTest,
            MenuPage.WeatherWarningTest,
            -> page = MenuPage.TestExecution
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
            unreadCount = unreadCount,
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
            onOpenNotifications = if (page == MenuPage.Home) {
                {
                    onTapSound()
                    page = MenuPage.NotificationHistory
                }
            } else {
                null
            },
        )

        when (page) {
            MenuPage.Home -> MenuHomeContent(
                onOpenClock = {
                    onTapSound()
                    page = MenuPage.Clock
                },
                onOpenMusic = {
                    onTapSound()
                    page = MenuPage.Music
                },
                onOpenDeviceControl = {
                    onTapSound()
                    page = MenuPage.DeviceControl
                },
                onOpenWeather = {
                    onTapSound()
                    page = MenuPage.Weather
                },
                onOpenWeatherWarning = {
                    onTapSound()
                    page = MenuPage.WeatherWarning
                },
                onOpenCalendar = {
                    onTapSound()
                    page = MenuPage.Calendar
                },
                onOpenObservances = {
                    onTapSound()
                    page = MenuPage.Observances
                },
                onOpenExpiration = {
                    onTapSound()
                    page = MenuPage.Expiration
                },
                onOpenSteps = {
                    onTapSound()
                    page = MenuPage.Steps
                },
                onOpenAwayAlerts = {
                    onTapSound()
                    awayAlertsUnlocked = false
                    page = MenuPage.AwayAlerts
                },
                onOpenTransit = {
                    onTapSound()
                    onRefreshTransit()
                    page = MenuPage.TransitInfo
                },
                onOpenTrainTimetable = {
                    onTapSound()
                    page = MenuPage.TrainTimetable
                },
                onOpenBusTimetable = {
                    onTapSound()
                    page = MenuPage.BusTimetable
                },
                onOpenMemorialDays = {
                    onTapSound()
                    page = MenuPage.MemorialDays
                },
                onOpenGarbageDays = {
                    onTapSound()
                    page = MenuPage.GarbageDays
                },
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.MemorialDays -> MemorialDayContent(
                now = now,
                repository = memorialRepository,
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
            MenuPage.GarbageDays -> MenuSubpageColumn(
                title = "ごみの日",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                GarbageDayRows(now)
            }
            MenuPage.Settings -> SettingsContent(
                notificationAnnounceSettings = notificationAnnounceSettings,
                onOpenNotificationSettings = {
                    onTapSound()
                    page = MenuPage.NotificationSettings
                },
                onOpenJihouSettings = {
                    onTapSound()
                    page = MenuPage.JihouSettings
                },
                onOpenCalendarSettings = {
                    onTapSound()
                    openWithCalendarPermission(MenuPage.CalendarSettings)
                },
                onOpenVolumeBrightness = {
                    onTapSound()
                    page = MenuPage.VolumeBrightness
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
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.NotificationSettings -> NotificationSettingsContent(
                settings = notificationAnnounceSettings,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.JihouSettings -> JihouSettingsContent(
                now = now,
                settings = jihouSettings,
                onOpenTodayTheme = {
                    onTapSound()
                    page = MenuPage.JihouTodayTheme
                },
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.JihouTodayTheme -> JihouTodayThemeContent(
                now = now,
                settings = jihouSettings,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.CalendarSettings -> CalendarSettingsContent(
                settings = calendarSettings,
                repository = calendarRepository,
                hasPermission = hasCalendarPermission,
                onRequestPermission = {
                    onTapSound()
                    permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                },
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.VolumeBrightness -> VolumeBrightnessContent(
                nightModeSettings = nightModeSettings,
                onOpenNightMode = {
                    onTapSound()
                    page = MenuPage.NightMode
                },
                onOpenEarthquakeSettings = {
                    onTapSound()
                    page = MenuPage.EarthquakeSettings
                },
                onOpenAwayAlertVolume = {
                    onTapSound()
                    page = MenuPage.AwayAlertVolume
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.AwayAlertVolume -> AwayAlertVolumeContent(
                settings = awayAlertSettings,
                onPreviewVolume = onPreviewAwayVolume,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.NightMode -> NightModeContent(
                settings = nightModeSettings,
                chimeController = chimeController,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.EarthquakeSettings -> EarthquakeSettingsContent(
                settings = earthquakeSettings,
                onPreviewVolume = onPreviewEewVolume,
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
                onOpenEarthquakeTest = {
                    onTapSound()
                    page = MenuPage.EarthquakeTest
                },
                onOpenWeatherCalendarTest = {
                    onTapSound()
                    page = MenuPage.WeatherCalendarTest
                },
                onOpenWeatherWarningTest = {
                    onTapSound()
                    page = MenuPage.WeatherWarningTest
                },
                onScheduleSplitTest = {
                    onTapSound()
                    onScheduleSplitTest()
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
            MenuPage.EarthquakeTest -> EarthquakeTestContent(
                onEewTest = {
                    onTapSound()
                    onEewTest()
                },
                onEarthquakeIntensityTest = { scale ->
                    onTapSound()
                    onEarthquakeIntensityTest(scale)
                },
                onEarthquakeIntensityUpdateTest = {
                    onTapSound()
                    onEarthquakeIntensityUpdateTest()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.WeatherCalendarTest -> WeatherCalendarTestContent(
                onNoonNoticeTest = {
                    onTapSound()
                    onNoonNoticeTest()
                },
                onLateNoticeTest = {
                    onTapSound()
                    onLateNoticeTest()
                },
                onSunsetTest = {
                    onTapSound()
                    onSunsetTest()
                },
                onMemorialAnnounceTest = {
                    onTapSound()
                    onMemorialAnnounceTest()
                },
                onQuoteAnnounceTest = {
                    onTapSound()
                    onQuoteAnnounceTest()
                },
                onExpirationAnnounceTest = {
                    onTapSound()
                    onExpirationAnnounceTest()
                },
                onWeatherAnnounceTest = { kind ->
                    onTapSound()
                    onWeatherAnnounceTest(kind)
                },
                onStepsAnnounceTest = {
                    onTapSound()
                    scope.launch {
                        val available = StepsAccess.isSdkAvailable(context)
                        val granted = withContext(Dispatchers.IO) {
                            StepsAccess.hasPermission(context)
                        }
                        when {
                            granted -> onStepsAnnounceTest()
                            available -> {
                                pendingStepsTest = true
                                stepsPermissionLauncher.launch(StepsAccess.permissionRequest())
                            }
                            else -> onStepsAnnounceTest()
                        }
                    }
                },
                onMorningBriefTest = {
                    onTapSound()
                    onMorningBriefTest()
                },
                onCalendarAnnounceTest = { kind ->
                    onTapSound()
                    if (hasCalendarPermission) {
                        onCalendarAnnounceTest(kind)
                    } else {
                        pendingCalendarTest = kind
                        permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                    }
                },
                onCalendarSoonTest = {
                    onTapSound()
                    onCalendarSoonTest()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.WeatherWarningTest -> WeatherWarningTestContent(
                onIssueTest = { severity ->
                    onTapSound()
                    onWeatherWarningTest(severity)
                },
                onCancelTest = { severity ->
                    onTapSound()
                    onWeatherWarningCancelTest(severity)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Clock -> ClockHubPage(
                timerState = timerState,
                alarmState = alarmState,
                now = now,
                onOpenTimer = {
                    onTapSound()
                    page = MenuPage.Timer
                },
                onOpenAlarm = {
                    onTapSound()
                    page = MenuPage.Alarm
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Timer -> TimerPage(
                state = timerState,
                now = now,
                onStart = { durationMillis ->
                    timer.start(durationMillis)
                    onTimerStarted()
                },
                onStop = { timer.stop() },
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Alarm -> AlarmPage(
                state = alarmState,
                onStart = { hour, minute ->
                    timer.startAlarm(hour, minute)
                    onTimerStarted()
                },
                onStop = { timer.stopAlarm() },
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Music -> MusicContent(
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
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
            MenuPage.Weather -> WeatherContent(
                now = now,
                repository = weatherRepository,
                weatherLastFetchedAt = weatherLastFetchedAt,
                earthquakeLastPolledAt = earthquakeLastPolledAt,
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.WeatherWarning -> WeatherWarningContent(
                now = now,
                warnings = activeWeatherWarnings,
                lastFetchedAt = weatherWarningLastFetchedAt,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.AwayAlerts -> if (awayAlertsUnlocked) {
                AwayAlertListPage(
                    archive = awayAlerts,
                    onOpenPhotos = { id ->
                        selectedAwayAlertId = id
                        page = MenuPage.AwayAlertPhotos
                    },
                    onOpenSettings = { openAppSettings() },
                    onTapSound = onTapSound,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                AwayAlertMenuLock(
                    controller = awayAlertController,
                    onUnlocked = { awayAlertsUnlocked = true },
                    onTapSound = onTapSound,
                    onBeep = onErrorSound,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            MenuPage.AwayAlertPhotos -> AwayAlertTimelinePage(
                archive = awayAlerts,
                alertId = selectedAwayAlertId,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Steps -> StepsPage(
                today = now.toLocalDate(),
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Expiration -> ExpirationContent(
                today = now.toLocalDate(),
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.Calendar -> CalendarContent(
                now = now,
                settings = calendarSettings,
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
            MenuPage.TrainTimetable -> TrainTimetableContent(
                now = now,
                repository = trainTimetableRepository,
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.TransitInfo -> TransitInfoContent(
                now = now,
                operations = operations,
                lastFetchedAt = transitLastFetchedAt,
                onRefresh = {
                    onTapSound()
                    onRefreshTransit()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.BusTimetable -> BusTimetableContent(
                now = now,
                repository = busTimetableRepository,
                reminderScheduler = busReminderScheduler,
                scheduledTrips = busScheduledTrips,
                onOpenReminders = {
                    page = MenuPage.BusReminders
                },
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.BusReminders -> BusRemindersContent(
                now = now,
                reminderScheduler = busReminderScheduler,
                scheduledTrips = busScheduledTrips,
                onTapSound = onTapSound,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            MenuPage.NotificationHistory -> NotificationHistoryPanel(
                items = history,
                onItemClick = { item ->
                    onTapSound()
                    notifications.markRead(item.id)
                    notifications.remove(item.id)
                },
                onClearAll = {
                    onTapSound()
                    notifications.clearHistory()
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
    unreadCount: Int,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onOpenNotifications: (() -> Unit)?,
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
            if (onOpenNotifications != null) {
                Panel(onClick = onOpenNotifications) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "通知",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (unreadCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE53935)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = unreadCount.coerceAtMost(9).toString(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
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
    onOpenClock: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenDeviceControl: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenWeatherWarning: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenObservances: () -> Unit,
    onOpenExpiration: () -> Unit,
    onOpenSteps: () -> Unit,
    onOpenAwayAlerts: () -> Unit,
    onOpenTransit: () -> Unit,
    onOpenTrainTimetable: () -> Unit,
    onOpenBusTimetable: () -> Unit,
    onOpenMemorialDays: () -> Unit,
    onOpenGarbageDays: () -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val menuItems = listOf(
        "クロック" to onOpenClock,
        "メディア" to onOpenMusic,
        "機器操作" to onOpenDeviceControl,
        "天気予報" to onOpenWeather,
        "気象警報" to onOpenWeatherWarning,
        "カレンダー" to onOpenCalendar,
        "行事" to onOpenObservances,
        "賞味期限管理" to onOpenExpiration,
        "歩数計" to onOpenSteps,
        "アラート" to onOpenAwayAlerts,
        "運行情報" to onOpenTransit,
        "鉄道時刻表" to onOpenTrainTimetable,
        "バス時刻表" to onOpenBusTimetable,
        "記念日" to onOpenMemorialDays,
        "ごみの日" to onOpenGarbageDays,
    )
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
            val rows = menuItems.chunked(3)
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
                        repeat(3 - rowItems.size) {
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
private fun TransitInfoContent(
    now: LocalDateTime,
    operations: Map<TrainLine, LineOperation>,
    lastFetchedAt: LocalDateTime?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(top = 28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "運行情報",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            RefreshChip(onClick = onRefresh)
        }
        QuietLastUpdatedLabel(
            text = formatLastUpdatedLabel(lastFetchedAt, now),
        )
        TrainLine.entries.forEach { line ->
            val operation = operations[line]
            Panel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Image(
                        painter = painterResource(line.iconResId),
                        contentDescription = line.displayName,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = line.displayName,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (operation != null) {
                        OutlinedStatusMark(
                            mark = operation.kind.toStatusMark(),
                            fontSizeSp = 40f,
                            strokeWidth = 4.5f,
                            bodyStrokeWidth = 7f,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (operation == null) {
                    Text(
                        text = "情報を取得中です…",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                    )
                } else {
                    Text(
                        text = operation.statusLabel,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = operation.detail,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    operation.updatedAtText?.let { updated ->
                        Text(
                            text = updated,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(
    notificationAnnounceSettings: NotificationAnnounceSettings,
    onOpenNotificationSettings: () -> Unit,
    onOpenJihouSettings: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    onOpenVolumeBrightness: () -> Unit,
    onOpenTestExecution: () -> Unit,
    onOpenNetworkLog: () -> Unit,
    onOpenSoundTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speech = if (notificationAnnounceSettings.speechEnabled) "読み上げオン" else "読み上げオフ"
    val notificationSubtitle = if (notificationAnnounceSettings.includeSilent) {
        "$speech・サイレントも"
    } else {
        speech
    }
    MenuSubpageColumn(title = "設定", modifier = modifier) {
        SettingsItem(
            title = "通知設定",
            subtitle = notificationSubtitle,
            onClick = onOpenNotificationSettings,
        )
        SettingsItem(
            title = "時報設定",
            onClick = onOpenJihouSettings,
        )
        SettingsItem(
            title = "カレンダー設定",
            onClick = onOpenCalendarSettings,
        )
        SettingsItem(
            title = "音量・明るさ",
            onClick = onOpenVolumeBrightness,
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
private fun VolumeBrightnessContent(
    nightModeSettings: NightModeSettings,
    onOpenNightMode: () -> Unit,
    onOpenEarthquakeSettings: () -> Unit,
    onOpenAwayAlertVolume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "音量・明るさ", modifier = modifier) {
        SettingsItem(
            title = "ナイトモード",
            subtitle = if (nightModeSettings.enabled) "オン・0:00〜6:00" else "オフ",
            onClick = onOpenNightMode,
        )
        SettingsItem(
            title = "緊急警報音量",
            onClick = onOpenEarthquakeSettings,
        )
        SettingsItem(
            title = "外出アラート音量",
            onClick = onOpenAwayAlertVolume,
        )
    }
}

@Composable
private fun whiteSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Color.White.copy(alpha = 0.85f),
    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
)

@Composable
private fun RatioPreviewPanel(
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    onPreview: () -> Unit,
    onTapSound: () -> Unit,
    onSlideFinished: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val percent = (ratio * 100f).roundToInt()
    Panel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$percent%",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = ratio,
            onValueChange = { value ->
                onRatioChange(value)
                previewJob?.cancel()
                previewJob = scope.launch {
                    delay(450L)
                    onPreview()
                }
            },
            onValueChangeFinished = { onSlideFinished?.invoke() },
            valueRange = 0f..1f,
            colors = whiteSliderColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "試す",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 20.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable {
                        onTapSound()
                        previewJob?.cancel()
                        onPreview()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AwayAlertVolumeContent(
    settings: AwayAlertSettings,
    onPreviewVolume: (Float) -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "外出アラート音量", modifier = modifier) {
        Text(
            text = "外出モードのアラートが発動しているあいだ、音楽と案内をこの音量で鳴らします",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        RatioPreviewPanel(
            ratio = settings.volumeRatio,
            onRatioChange = settings::updateVolumeRatio,
            onPreview = { onPreviewVolume(settings.volumeRatio) },
            onTapSound = onTapSound,
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
private fun EarthquakeSettingsContent(
    settings: EarthquakeSettings,
    onPreviewVolume: (Float) -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "緊急警報音量", modifier = modifier) {
        Text(
            text = "緊急地震速報・気象特別／危険警報の発令時に一時的に上げる音量です",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        RatioPreviewPanel(
            ratio = settings.eewVolumeRatio,
            onRatioChange = settings::updateEewVolumeRatio,
            onPreview = { onPreviewVolume(settings.eewVolumeRatio) },
            onTapSound = onTapSound,
        )
    }
}

@Composable
private fun TestExecutionContent(
    onOpenJihouTest: () -> Unit,
    onOpenEarthquakeTest: () -> Unit,
    onOpenWeatherCalendarTest: () -> Unit,
    onOpenWeatherWarningTest: () -> Unit,
    onScheduleSplitTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "テスト実行", modifier = modifier) {
SettingsItem(
            title = "時報テスト",
            subtitle = "モード・四半期・特殊正時",
            onClick = onOpenJihouTest,
        )
        SettingsItem(
            title = "運行情報テスト",
            subtitle = "即時に通知",
            onClick = onScheduleSplitTest,
        )
        SettingsItem(
            title = "地震情報",
            subtitle = "緊急地震速報・震度情報",
            onClick = onOpenEarthquakeTest,
        )
        SettingsItem(
            title = "デイリー通知",
            subtitle = "朝のまとめ・昼のまとめ・日の入り・夜の通知",
            onClick = onOpenWeatherCalendarTest,
        )
        SettingsItem(
            title = "気象警報",
            subtitle = "特別警報・危険警報・警報・注意報",
            onClick = onOpenWeatherWarningTest,
        )
    }
}

@Composable
private fun NightModeContent(
    settings: NightModeSettings,
    chimeController: HourlyChimeController,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var canWriteBrightness by remember {
        mutableStateOf(canWriteSystemBrightness(context))
    }
    val brightnessPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        canWriteBrightness = canWriteSystemBrightness(context)
    }
    val scope = rememberCoroutineScope()
    var brightnessRestoreJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(settings, chimeController) {
        onDispose {
            brightnessRestoreJob?.cancel()
            chimeController.stopVolumePreview()
            settings.setBrightnessPreview(null)
        }
    }
    val percent = (settings.brightness * 100f).roundToInt()
    MenuSubpageColumn(title = "ナイトモード", modifier = modifier) {
        Panel(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "ナイトモード",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (settings.enabled) "オン・0:00〜6:00" else "オフ",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        onTapSound()
                        settings.updateEnabled(enabled)
                        if (enabled && !canWriteSystemBrightness(context)) {
                            brightnessPermissionLauncher.launch(
                                systemBrightnessSettingsIntent(context),
                            )
                        }
                    },
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
        Text(
            text = "0:00〜6:00は明るさの自動を切り、この割合に固定します。通知が出ているあいだと、メニュー中、操作から60秒間は、切り替える前の明るさに戻します。通知が消えるとこの割合に戻します。緊急の全画面案内と外出アラートは最大の明るさにし、半分の通知になると切り替える前の明るさ、それが消えるとこの割合に戻します。6:00を過ぎるかオフにすると、元の自動設定と明るさに戻します。",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        if (!canWriteBrightness) {
            SettingsItem(
                title = "明るさの変更を許可",
                subtitle = "自動を切るために必要です",
                onClick = {
                    onTapSound()
                    brightnessPermissionLauncher.launch(systemBrightnessSettingsIntent(context))
                },
            )
        }
        Text(
            text = "時報の音量",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "ナイトモード中の時報と、「x時です」の読み上げをこの大きさにします。100%が通常の時報です。通知の読み上げは通常の音量、緊急通知は緊急警報音量のままです。",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        RatioPreviewPanel(
            ratio = settings.chimeVolumeRatio,
            onRatioChange = settings::updateChimeVolumeRatio,
            onPreview = { chimeController.previewChimeVolume(settings.chimeVolumeRatio) },
            onTapSound = onTapSound,
            onSlideFinished = onTapSound,
        )
        Text(
            text = "画面の明るさ",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "動かしているあいだはこの明るさになり、離して1秒後に元の明るさへ戻ります。",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        Panel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$percent%",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = settings.brightness,
                onValueChange = { value ->
                    settings.updateBrightness(value)
                    brightnessRestoreJob?.cancel()
                    settings.setBrightnessPreview(value)
                },
                onValueChangeFinished = {
                    onTapSound()
                    brightnessRestoreJob?.cancel()
                    brightnessRestoreJob = scope.launch {
                        delay(1_000L)
                        settings.setBrightnessPreview(null)
                    }
                },
                valueRange = NightModeMinBrightness..NightModeMaxBrightness,
                colors = whiteSliderColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun EarthquakeTestContent(
    onEewTest: () -> Unit,
    onEarthquakeIntensityTest: (Int?) -> Unit,
    onEarthquakeIntensityUpdateTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "地震情報", modifier = modifier) {
SettingsItem(
            title = "緊急地震速報テスト",
            subtitle = "全画面警告と読み上げ",
            onClick = onEewTest,
        )
        SettingsItem(
            title = "震度情報テスト（順送り）",
            subtitle = "1→2→3→4→5弱→5強→6弱→6強→7",
            onClick = { onEarthquakeIntensityTest(null) },
        )
        SettingsItem(
            title = "震度情報テスト（震度3）",
            subtitle = "初報",
            onClick = { onEarthquakeIntensityTest(30) },
        )
        SettingsItem(
            title = "震度情報テスト（震度5弱）",
            subtitle = "初報",
            onClick = { onEarthquakeIntensityTest(45) },
        )
        SettingsItem(
            title = "震度情報テスト（震度5強）",
            subtitle = "初報",
            onClick = { onEarthquakeIntensityTest(50) },
        )
        SettingsItem(
            title = "震度情報テスト（震度7）",
            subtitle = "初報",
            onClick = { onEarthquakeIntensityTest(70) },
        )
        SettingsItem(
            title = "震度更新テスト",
            subtitle = "震度3のあと5強へ更新",
            onClick = onEarthquakeIntensityUpdateTest,
        )
    }
}

@Composable
private fun WeatherCalendarTestContent(
    onMorningBriefTest: () -> Unit,
    onNoonNoticeTest: () -> Unit,
    onLateNoticeTest: () -> Unit,
    onSunsetTest: () -> Unit,
    onMemorialAnnounceTest: () -> Unit,
    onQuoteAnnounceTest: () -> Unit,
    onExpirationAnnounceTest: () -> Unit,
    onWeatherAnnounceTest: (WeatherAnnounceKind) -> Unit,
    onStepsAnnounceTest: () -> Unit,
    onCalendarAnnounceTest: (CalendarAnnounceKind) -> Unit,
    onCalendarSoonTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "デイリー通知", modifier = modifier) {
        Text(
            text = "時報のあと",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
        )
        SettingsItem(
            title = "朝のまとめ",
            subtitle = "今日の天気、今日の予定、運行、今日のごみ、賞味期限",
            onClick = onMorningBriefTest,
        )
        SettingsItem(
            title = "昼のまとめ",
            subtitle = "記念日、このあとの天気、このあとの予定、賞味期限、格言",
            onClick = onNoonNoticeTest,
        )
        SettingsItem(
            title = "日の入り",
            subtitle = "読み上げのあと、このあとの天気と予定",
            onClick = onSunsetTest,
        )
        SettingsItem(
            title = "夜の通知",
            subtitle = "歩数、明日の天気、明日の予定、明日のごみ、賞味期限",
            onClick = onLateNoticeTest,
        )
        Text(
            text = "個別の読み上げ",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp),
        )
        ReadoutGenreLabel("天気")
        SettingsItem(
            title = "今日の天気（朝）",
            subtitle = "天気予報読み上げテスト",
            onClick = { onWeatherAnnounceTest(WeatherAnnounceKind.Today) },
        )
        SettingsItem(
            title = "このあとの天気（昼）",
            subtitle = "天気予報読み上げテスト",
            onClick = { onWeatherAnnounceTest(WeatherAnnounceKind.RestOfToday) },
        )
        SettingsItem(
            title = "明日の天気（夜）",
            subtitle = "天気予報読み上げテスト",
            onClick = { onWeatherAnnounceTest(WeatherAnnounceKind.Tomorrow) },
        )
        ReadoutGenreLabel("予定")
        SettingsItem(
            title = "今日の予定（朝）",
            subtitle = "カレンダー読み上げテスト",
            onClick = { onCalendarAnnounceTest(CalendarAnnounceKind.Today) },
        )
        SettingsItem(
            title = "このあとの予定（昼）",
            subtitle = "カレンダー読み上げテスト",
            onClick = { onCalendarAnnounceTest(CalendarAnnounceKind.RestOfToday) },
        )
        SettingsItem(
            title = "明日の予定（夜）",
            subtitle = "カレンダー読み上げテスト",
            onClick = { onCalendarAnnounceTest(CalendarAnnounceKind.Tomorrow) },
        )
        SettingsItem(
            title = "15分前",
            subtitle = "まもなく予定の読み上げ",
            onClick = onCalendarSoonTest,
        )
        ReadoutGenreLabel("賞味期限")
        SettingsItem(
            title = "期限が近いもの",
            subtitle = "賞味期限の読み上げ",
            onClick = onExpirationAnnounceTest,
        )
        ReadoutGenreLabel("記念日")
        SettingsItem(
            title = "今日の記念日",
            subtitle = "認定記念日の読み上げ",
            onClick = onMemorialAnnounceTest,
        )
        ReadoutGenreLabel("歩数")
        SettingsItem(
            title = "歩数（夜）",
            subtitle = "今日と今週の歩数を読み上げ",
            onClick = onStepsAnnounceTest,
        )
        ReadoutGenreLabel("格言")
        SettingsItem(
            title = "今日の格言",
            subtitle = "ランダムに1件読み上げ",
            onClick = onQuoteAnnounceTest,
        )
    }
}

@Composable
private fun ReadoutGenreLabel(title: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun WeatherWarningTestContent(
    onIssueTest: (WeatherWarningSeverity) -> Unit,
    onCancelTest: (WeatherWarningSeverity) -> Unit,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "気象警報", modifier = modifier) {
SettingsItem(
            title = "特別警報（大雨）",
            onClick = { onIssueTest(WeatherWarningSeverity.Special) },
        )
        SettingsItem(
            title = "危険警報（大雨）",
            onClick = { onIssueTest(WeatherWarningSeverity.Danger) },
        )
        SettingsItem(
            title = "警報（大雨）",
            onClick = { onIssueTest(WeatherWarningSeverity.Warning) },
        )
        SettingsItem(
            title = "注意報（大雨）",
            onClick = { onIssueTest(WeatherWarningSeverity.Advisory) },
        )
        SettingsItem(
            title = "解除（大雨注意報）",
            onClick = { onCancelTest(WeatherWarningSeverity.Advisory) },
        )
        SettingsItem(
            title = "解除（大雨警報）",
            onClick = { onCancelTest(WeatherWarningSeverity.Warning) },
        )
        SettingsItem(
            title = "解除（大雨危険警報）",
            onClick = { onCancelTest(WeatherWarningSeverity.Danger) },
        )
        SettingsItem(
            title = "解除（大雨特別警報）",
            onClick = { onCancelTest(WeatherWarningSeverity.Special) },
        )
    }
}

@Composable
private fun WeatherWarningContent(
    now: LocalDateTime,
    warnings: List<WeatherWarningItem>,
    lastFetchedAt: LocalDateTime?,
    modifier: Modifier = Modifier,
) {
    MenuSubpageColumn(title = "気象警報", modifier = modifier) {
        Text(
            text = "小平市",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        QuietLastUpdatedLabel(
            text = formatLastUpdatedLabel(lastFetchedAt, now),
        )
        if (warnings.isEmpty()) {
            Text(
                text = "発表中の警報・注意報はありません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 18.sp,
            )
        } else {
            warnings.forEach { item ->
                Panel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = item.name,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = severityLabel(item.severity),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (item.detailTexts.isNotEmpty()) {
                        Text(
                            text = item.detailTexts.joinToString("、"),
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 16.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    item.reportAt?.let { at ->
                        Text(
                            text = "発表 ${at.hour}:${at.minute.toString().padStart(2, '0')}",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun severityLabel(severity: WeatherWarningSeverity): String {
    return when (severity) {
        WeatherWarningSeverity.Special -> "特別警報"
        WeatherWarningSeverity.Danger -> "危険警報"
        WeatherWarningSeverity.Warning -> "警報"
        WeatherWarningSeverity.Advisory -> "注意報"
    }
}

@Composable
private fun JihouTestContent(
    chimeController: HourlyChimeController,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSeason by remember { mutableStateOf(JihouSeason.Normal) }
    val seasons = remember { JihouSeason.entries.toList() }
    val specials = remember(selectedSeason) { selectedSeason.specialIntros() }
    MenuSubpageColumn(title = "時報テスト", modifier = modifier) {
        Text(
            text = "モード",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
        )
        seasons.forEach { season ->
            val selected = season == selectedSeason
            SettingsItem(
                title = season.displayName,
                subtitle = if (selected) "選択中" else "タップで選択",
                selected = selected,
                onClick = {
                    onTapSound()
                    selectedSeason = season
                },
            )
        }
        Text(
            text = "再生",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        SettingsItem(
            title = "ランダム再生",
            subtitle = "四半期メロディをランダムに1本",
            onClick = {
                onTapSound()
                chimeController.playTestRandom(selectedSeason)
            },
        )
        listOf(0, 15, 30, 45).forEach { minute ->
            val label = if (minute == 0) "毎時0分" else "毎時${minute}分"
            SettingsItem(
                title = label,
                subtitle = "メロディと時刻読み上げ",
                onClick = {
                    onTapSound()
                    chimeController.playTestQuarter(minute, selectedSeason)
                },
            )
        }
        Text(
            text = "特殊時刻",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        specials.forEach { intro ->
            SettingsItem(
                title = intro.timeLabel,
                subtitle = "イントロ → 時報メロディ → 読み上げ",
                onClick = {
                    onTapSound()
                    chimeController.playTestSpecial(
                        hour = intro.hour,
                        minute = intro.minute,
                        testSeason = selectedSeason,
                    )
                },
            )
        }
    }
}

@Composable
private fun WeatherContent(
    now: LocalDateTime,
    repository: WeatherRepository,
    weatherLastFetchedAt: LocalDateTime?,
    earthquakeLastPolledAt: LocalDateTime?,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var days by remember { mutableStateOf<List<DailyWeather>?>(null) }
    var todayAstro by remember { mutableStateOf<DailyWeather?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var requestId by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun loadForecast(forceRefresh: Boolean) {
        val id = requestId + 1
        requestId = id
        loading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.loadForecastResult(now, forceRefresh = forceRefresh)
            }
            if (id != requestId) {
                return@launch
            }
            val forecast = result.forecast
            if (forecast != null) {
                days = repository.menuDays(forecast, now.toLocalDate())
                todayAstro = forecast.daily.firstOrNull { it.date == now.toLocalDate() }
                error = null
            } else if (days == null) {
                error = "天気を取得できませんでした"
            }
            if (result.fetchFailed && InternetConnectivity.hasInternet()) {
                onErrorSound()
            }
            loading = false
        }
    }

    LaunchedEffect(now.toLocalDate()) {
        loadForecast(forceRefresh = true)
    }
    val dayLabels = listOf("今日", "明日", "明後日")
    val moon = remember(now.toLocalDate()) { moonPhaseInfo(now.toLocalDate()) }
    val sunFormatter = remember { DateTimeFormatter.ofPattern("H:mm") }
    MenuSubpageColumn(
        title = "天気予報",
        modifier = modifier,
        headerAction = {
            RefreshChip(
                enabled = !loading,
                onClick = {
                    onTapSound()
                    loadForecast(forceRefresh = true)
                },
            )
        },
    ) {
        Text(
            text = "小平市",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        QuietLastUpdatedLabel(
            text = "天気 ${formatLastUpdatedLabel(weatherLastFetchedAt, now)}",
        )
        QuietLastUpdatedLabel(
            text = "地震 ${formatLastUpdatedLabel(earthquakeLastPolledAt, now)}",
        )
        WeatherAstroPanel(
            now = now,
            sunrise = todayAstro?.sunrise,
            sunset = todayAstro?.sunset,
            moonLabel = moon.label,
            moonIlluminationPercent = moon.illuminationPercent,
            sunFormatter = sunFormatter,
        )
        when {
            loading && days == null -> {
                Text(
                    text = "読み込み中…",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 18.sp,
                )
            }
            error != null && days == null -> {
                Text(
                    text = error.orEmpty(),
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 18.sp,
                )
            }
            else -> {
                val items = days.orEmpty()
                if (items.isEmpty()) {
                    Text(
                        text = "表示できる予報がありません",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                    )
                } else {
                    items.forEachIndexed { index, day ->
                        WeatherDayRow(
                            label = dayLabels.getOrElse(index) { day.date.toString() },
                            day = day,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherAstroPanel(
    now: LocalDateTime,
    sunrise: LocalTime?,
    sunset: LocalTime?,
    moonLabel: String,
    moonIlluminationPercent: Int,
    sunFormatter: DateTimeFormatter,
) {
    val daylight = daylightRemainingLabel(now, sunrise, sunset)
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "日の出・日の入り",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                )
                Text(
                    text = "日の出 ${sunrise?.format(sunFormatter) ?: "--:--"}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "日の入り ${sunset?.format(sunFormatter) ?: "--:--"}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (daylight != null) {
                    Text(
                        text = daylight,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "月齢",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 16.sp,
                )
                Text(
                    text = moonLabel,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "照らされ ${moonIlluminationPercent}%",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun QuietLastUpdatedLabel(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 15.sp,
    )
}

@Composable
private fun WeatherDayRow(
    label: String,
    day: DailyWeather,
) {
    val max = day.temperatureMaxC.roundToInt()
    val min = day.temperatureMinC.roundToInt()
    val weatherText = weatherDescriptionJa(day.weatherCode)
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(72.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                WeatherIcon(
                    weatherCode = day.weatherCode,
                    size = 72.dp,
                )
                Text(
                    text = weatherText,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "最高 $max℃",
                    color = Color(0xFFFF8A80),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "最低 $min℃",
                    color = Color(0xFF82B1FF),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CalendarContent(
    now: LocalDateTime,
    settings: CalendarSettings,
    repository: CalendarRepository,
    hasPermission: Boolean,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sections by remember { mutableStateOf<CalendarDaySections?>(null) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    LaunchedEffect(hasPermission, settings.disabledCalendarIds, now.toLocalDate()) {
        if (!hasPermission) {
            sections = null
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            val all = repository.loadCalendars()
            val enabledIds = settings.enabledIdsAmong(all)
            all to repository.loadMenuSections(enabledIds, now)
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
private fun CalendarSettingsContent(
    settings: CalendarSettings,
    repository: CalendarRepository,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            calendars = emptyList()
            return@LaunchedEffect
        }
        calendars = withContext(Dispatchers.IO) { repository.loadCalendars() }
    }
    MenuSubpageColumn(title = "カレンダー設定", modifier = modifier) {
        Text(
            text = "読み上げと一覧に使うカレンダーを選んでください",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        if (!hasPermission) {
            SettingsItem(
                title = "カレンダーへのアクセスを許可",
                subtitle = "設定を変更するために必要です",
                onClick = onRequestPermission,
            )
            return@MenuSubpageColumn
        }
        if (calendars.isEmpty()) {
            Text(
                text = "カレンダーが見つかりません",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 18.sp,
            )
            return@MenuSubpageColumn
        }
        calendars.forEach { calendar ->
            val enabled = settings.isEnabled(calendar.id)
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
                            text = calendar.displayName,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        calendar.accountName?.let { account ->
                            Text(
                                text = account,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 16.sp,
                            )
                        }
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            onTapSound()
                            settings.setEnabled(calendar.id, checked)
                        },
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
    }
}

@Composable
private fun NotificationSettingsContent(
    settings: NotificationAnnounceSettings,
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
    }
}

@Composable
private fun JihouSettingsContent(
    now: LocalDateTime,
    settings: JihouSettings,
    onOpenTodayTheme: () -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val automatic = settings.automaticSeason(now)
    val effective = settings.effectiveSeason(now)
    val hasOverride = settings.hasTodayOverride(now)
    MenuSubpageColumn(title = "時報設定", modifier = modifier) {
Panel(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "時報",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (settings.enabled) "オン" else "オフ",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        onTapSound()
                        settings.updateEnabled(enabled)
                    },
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
        SettingsItem(
            title = "今日だけ切り替え",
            subtitle = if (hasOverride) {
                "今日のみ ${effective.displayName}（自動は${automatic.displayName}）"
            } else {
                "自動 ${automatic.displayName}"
            },
            onClick = onOpenTodayTheme,
        )
    }
}

@Composable
private fun JihouTodayThemeContent(
    now: LocalDateTime,
    settings: JihouSettings,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val automatic = settings.automaticSeason(now)
    val hasOverride = settings.hasTodayOverride(now)
    val overrideSeason = settings.todayOverrideSeason.takeIf { hasOverride }
    val seasons = remember { JihouSeason.entries.toList() }
    MenuSubpageColumn(title = "今日だけ切り替え", modifier = modifier) {
        Text(
            text = "今日のテーマ日だけ使うテーマを選びます。翌日6時で自動に戻ります。",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 16.sp,
        )
        SettingsItem(
            title = "自動",
            subtitle = if (!hasOverride) {
                "選択中（${automatic.displayName}）"
            } else {
                "自動判定に戻す（${automatic.displayName}）"
            },
            selected = !hasOverride,
            onClick = {
                onTapSound()
                settings.clearTodayOverride()
            },
        )
        seasons.forEach { season ->
            val selected = overrideSeason == season
            SettingsItem(
                title = season.displayName,
                subtitle = when {
                    selected -> "選択中"
                    season == automatic -> "自動と同じ"
                    else -> "今日だけこのテーマ"
                },
                selected = selected,
                onClick = {
                    onTapSound()
                    settings.setTodayOverride(season, now)
                },
            )
        }
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
