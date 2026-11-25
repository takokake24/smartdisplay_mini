package app.smartdisplay

import android.app.Activity
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.smartdisplay.audio.LocalPlaybackDucker
import app.smartdisplay.audio.AwayAlertVolumeLock
import app.smartdisplay.away.AwayAlertScreen
import app.smartdisplay.away.rememberAwayAlertController
import app.smartdisplay.away.rememberAwayAlertSettings
import app.smartdisplay.audio.MusicVolumeBoost
import app.smartdisplay.audio.rememberEffectPlayer
import app.smartdisplay.bus.BusReminderScheduler
import app.smartdisplay.bus.BusTimetableRepository
import app.smartdisplay.train.TrainTimetableRepository
import app.smartdisplay.calendar.CalendarAnnounceKind
import app.smartdisplay.calendar.rememberCalendarAnnouncer
import app.smartdisplay.calendar.rememberCalendarSoonMonitor
import app.smartdisplay.calendar.rememberCalendarSettings
import app.smartdisplay.earthquake.EarthquakeAlertPhase
import app.smartdisplay.earthquake.EewFullscreen
import app.smartdisplay.earthquake.rememberEarthquakeAlertController
import app.smartdisplay.earthquake.rememberEarthquakeSettings
import app.smartdisplay.jihou.beginChimeExternalFetch
import app.smartdisplay.jihou.holidayAndSeasonalLabel
import app.smartdisplay.jihou.jihouThemeDate
import app.smartdisplay.jihou.rememberHourlyChimeController
import app.smartdisplay.jihou.rememberJihouSettings
import app.smartdisplay.brief.rememberMorningBriefAnnouncer
import app.smartdisplay.expiration.rememberExpirationAnnouncer
import app.smartdisplay.garbage.rememberGarbageAnnouncer
import app.smartdisplay.memorial.MemorialRepository
import app.smartdisplay.memorial.rememberMemorialAnnouncer
import app.smartdisplay.quote.rememberQuoteAnnouncer
import app.smartdisplay.jihou.rememberSunsetAnnouncer
import app.smartdisplay.night.NightModeBrightness
import app.smartdisplay.night.isNightModeHours
import app.smartdisplay.night.rememberNightModeSettings
import app.smartdisplay.notification.AppNotification
import app.smartdisplay.notification.NotificationBanner
import app.smartdisplay.media.SystemMedia
import app.smartdisplay.media.SystemMediaHub
import app.smartdisplay.media.rememberSystemMedia
import app.smartdisplay.notification.NotificationCategoryGeneral
import app.smartdisplay.notification.NotificationCategoryTransit
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.notification.NotificationStatusMark
import app.smartdisplay.notification.rememberDeviceNotificationAnnouncer
import app.smartdisplay.notification.rememberNotificationAnnounceSettings
import app.smartdisplay.notification.rememberNotificationCenter
import app.smartdisplay.speech.rememberAnnouncementGate
import app.smartdisplay.timer.AlarmPhase
import app.smartdisplay.timer.TimerPhase
import app.smartdisplay.timer.clockStatusLabel
import app.smartdisplay.timer.rememberTimerController
import app.smartdisplay.steps.rememberStepsAnnouncer
import app.smartdisplay.speech.rememberSpeechSpeaker
import app.smartdisplay.spotify.ArtworkFrame
import app.smartdisplay.spotify.SpotifyPlayer
import app.smartdisplay.spotify.SpotifySession
import app.smartdisplay.spotify.rememberSpotifyArtwork
import app.smartdisplay.transit.TrainLine
import app.smartdisplay.transit.rememberTransitMonitor
import app.smartdisplay.ui.SmartDisplayTheme
import app.smartdisplay.util.AppToastHost
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.millisUntilNextCacheMaintenance
import app.smartdisplay.util.pruneAppCaches
import app.smartdisplay.warning.WeatherWarningAlertPhase
import app.smartdisplay.warning.WeatherWarningFullscreen
import app.smartdisplay.warning.rememberWeatherWarningController
import app.smartdisplay.weather.SunClockLead
import app.smartdisplay.weather.SunEventIcon
import app.smartdisplay.weather.WeatherAnnounceKind
import app.smartdisplay.weather.WeatherRepository
import app.smartdisplay.weather.rememberWeatherAnnouncer
import app.smartdisplay.weather.rememberWeatherDailyFetch
import app.smartdisplay.weather.rememberWeatherImminentMonitor
import app.smartdisplay.weather.sunClockLead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private const val MenuIdleTimeoutMillis = 60_000L

@Composable
fun ClockScreen() {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }

    val context = LocalContext.current
    rememberChargingSupplyAlert()
    rememberLinkConnectionAlerts()
    DisposableEffect(context) {
        InternetConnectivity.start(context)
        onDispose { InternetConnectivity.stop() }
    }
    val online by InternetConnectivity.online.collectAsState()
    val effects = rememberEffectPlayer()
    val playTap: () -> Unit = remember(effects) { { effects.playTap() } }
    val playBeep: () -> Unit = remember(effects) { { effects.playBeep() } }
    val speechSpeaker = rememberSpeechSpeaker()
    val playbackDucker = remember(context) { LocalPlaybackDucker(context.applicationContext) }
    val announcementGate = rememberAnnouncementGate { active ->
        playbackDucker.setDucked(active)
    }
    DisposableEffect(playbackDucker) {
        onDispose { playbackDucker.setDucked(false) }
    }
    val jihouSettings = rememberJihouSettings()
    val notificationAnnounceSettings = rememberNotificationAnnounceSettings()
    val nightModeSettings = rememberNightModeSettings()
    val calendarSettings = rememberCalendarSettings()
    val earthquakeSettings = rememberEarthquakeSettings()
    val awayAlertSettings = rememberAwayAlertSettings()
    val systemMedia = rememberSystemMedia()
    StartupPermissionRequests(systemMedia)
    val spotifyPlayer = remember(context) { SpotifyPlayer(context.applicationContext) }
    val spotifyPlaying = remember { mutableStateOf(false) }
    val spotifyOnThisDevice = remember { mutableStateOf(false) }
    LaunchedEffect(spotifyPlayer) {
        while (true) {
            if (!SpotifySession.connected.value) {
                spotifyPlaying.value = false
                spotifyOnThisDevice.value = false
            } else {
                try {
                    val playback = spotifyPlayer.playback()
                    val playing = playback?.playing == true
                    spotifyPlaying.value = playing
                    val localId = SpotifySession.localDeviceId(context)
                    spotifyOnThisDevice.value = playing &&
                        !localId.isNullOrBlank() &&
                        playback.deviceId == localId
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
            delay(5_000)
        }
    }
    val chimeController = rememberHourlyChimeController(
        speaker = speechSpeaker,
        announcementGate = announcementGate,
        enabled = jihouSettings.enabled,
        settings = jihouSettings,
        playbackScale = { at ->
            val night = nightModeSettings.enabled && isNightModeHours(at)
            val mediaPlaying = systemMedia.deviceNowPlaying.value != null || spotifyPlaying.value
            if (night && !mediaPlaying) {
                nightModeSettings.chimeVolumeRatio
            } else {
                1f
            }
        },
    )
    val notifications = rememberNotificationCenter(
        speaker = speechSpeaker,
        effects = effects,
        announcementGate = announcementGate,
    )
    val timerController = rememberTimerController(
        notifications = notifications,
        announcementGate = announcementGate,
        speaker = speechSpeaker,
    )
    rememberDeviceNotificationAnnouncer(
        notifications = notifications,
        settings = notificationAnnounceSettings,
    )
    val earthquakeController = rememberEarthquakeAlertController(
        settings = earthquakeSettings,
        notifications = notifications,
        speaker = speechSpeaker,
        effects = effects,
        announcementGate = announcementGate,
        chimeController = chimeController,
    )
    val weatherWarningController = rememberWeatherWarningController(
        earthquakeSettings = earthquakeSettings,
        notifications = notifications,
        speaker = speechSpeaker,
        effects = effects,
        announcementGate = announcementGate,
        chimeController = chimeController,
    )
    val awayAlertController = rememberAwayAlertController(
        settings = awayAlertSettings,
        notifications = notifications,
        speaker = speechSpeaker,
        effects = effects,
        announcementGate = announcementGate,
        chimeController = chimeController,
        atHome = {
            spotifyOnThisDevice.value || systemMedia.deviceNowPlaying.value != null
        },
        yieldToHigherCrisis = {
            earthquakeController.phase.value == EarthquakeAlertPhase.FullScreen ||
                weatherWarningController.phase.value == WeatherWarningAlertPhase.FullScreen
        },
    )
    val weatherRepository = remember { WeatherRepository() }
    rememberWeatherDailyFetch(weatherRepository)
    val weatherAnnouncer = rememberWeatherAnnouncer(
        notifications = notifications,
        repository = weatherRepository,
    )
    rememberWeatherImminentMonitor(
        notifications = notifications,
        repository = weatherRepository,
    )
    val calendarAnnouncer = rememberCalendarAnnouncer(
        settings = calendarSettings,
        notifications = notifications,
    )
    val calendarSoonMonitor = rememberCalendarSoonMonitor(
        settings = calendarSettings,
        notifications = notifications,
    )
    val stepsAnnouncer = rememberStepsAnnouncer(notifications)
    val expirationAnnouncer = rememberExpirationAnnouncer(notifications)
    val garbageAnnouncer = rememberGarbageAnnouncer(
        notifications = notifications,
        announcementGate = announcementGate,
        chimeEnabled = { jihouSettings.enabled },
        afterTomorrow = { expirationAnnouncer.postCloser() },
    )
    val sunsetAnnouncer = rememberSunsetAnnouncer(
        repository = weatherRepository,
        notifications = notifications,
        weatherAnnouncer = weatherAnnouncer,
        calendarAnnouncer = calendarAnnouncer,
        announcementGate = announcementGate,
        chimeEnabled = { jihouSettings.enabled },
        playOverlapChime = { hour, minute ->
            chimeController.playScheduledChime(hour, minute)
        },
    )
    val transitMonitor = rememberTransitMonitor(notifications)
    val trainTimetableRepository = remember { TrainTimetableRepository() }
    val busTimetableRepository = remember { BusTimetableRepository() }
    val memorialRepository = remember { MemorialRepository() }
    val memorialAnnouncer = rememberMemorialAnnouncer(
        repository = memorialRepository,
        notifications = notifications,
    )
    val quoteAnnouncer = rememberQuoteAnnouncer(notifications)
    val morningBrief = rememberMorningBriefAnnouncer(
        notifications = notifications,
        weatherAnnouncer = weatherAnnouncer,
        calendarAnnouncer = calendarAnnouncer,
        transitMonitor = transitMonitor,
        garbageAnnouncer = garbageAnnouncer,
        expirationAnnouncer = expirationAnnouncer,
        announcementGate = announcementGate,
        chimeEnabled = { jihouSettings.enabled },
    )
    val busReminderScheduler = remember(notifications) { BusReminderScheduler(notifications) }
    val busScheduledTrips by busReminderScheduler.scheduledTrips.collectAsState()
    val transitMuteThemeDate = remember(now) { jihouThemeDate(now) }
    LaunchedEffect(transitMonitor, transitMuteThemeDate) {
        transitMonitor.setPollingSuppressed(
            notifications.isMutedToday(NotificationCategoryTransit),
        )
    }
    LaunchedEffect(now) {
        busReminderScheduler.pruneExpired(now)
    }
    LaunchedEffect(trainTimetableRepository, busTimetableRepository, weatherRepository) {
        while (true) {
            delay(millisUntilNextCacheMaintenance())
            pruneAppCaches(
                trainTimetableRepository = trainTimetableRepository,
                busTimetableRepository = busTimetableRepository,
                weatherRepository = weatherRepository,
            )
        }
    }
    LaunchedEffect(
        chimeController,
        stepsAnnouncer,
        memorialAnnouncer,
        weatherAnnouncer,
        calendarAnnouncer,
        expirationAnnouncer,
        quoteAnnouncer,
        transitMonitor,
        sunsetAnnouncer,
    ) {
        chimeController.setOnChimeStarted { time ->
            beginChimeExternalFetch(
                time = time,
                weatherAnnouncer = weatherAnnouncer,
                calendarAnnouncer = calendarAnnouncer,
                memorialAnnouncer = memorialAnnouncer,
                quoteAnnouncer = quoteAnnouncer,
                stepsAnnouncer = stepsAnnouncer,
                expirationAnnouncer = expirationAnnouncer,
                transitMonitor = transitMonitor,
                sunsetAnnouncer = sunsetAnnouncer,
            )
        }
        chimeController.setOnTimeAnnounced { time ->
            sunsetAnnouncer.noteChimeTime(time)
        }
        chimeController.setOnAfterTimeAnnounce { time ->
            stepsAnnouncer.onChimeFinished(time)
            memorialAnnouncer.onChimeFinished(time)
            weatherAnnouncer.onChimeFinished(time)
            calendarAnnouncer.onChimeFinished(time)
            if (time.hour == 12 && time.minute == 0) {
                expirationAnnouncer.postCloser(time.toLocalDate())
            }
            quoteAnnouncer.onChimeFinished(time)
        }
    }
    val connectionNotification = remember {
        AppNotification(
            id = "internet-offline",
            category = NotificationCategoryGeneral,
            title = "接続エラー",
            body = "インターネットに接続ができませんでした。",
            speechText = "インターネットに接続ができませんでした。",
            createdAt = LocalDateTime.now(),
            soundEffect = NotificationSoundEffect.Beep,
        )
    }
    LaunchedEffect(online) {
        if (online) {
            return@LaunchedEffect
        }
        var speaking = false
        try {
            announcementGate.awaitChimeSequenceIdle()
            if (InternetConnectivity.online.value) {
                return@LaunchedEffect
            }
            announcementGate.withLock {
                if (!announcementGate.shouldContinueSpeech()) {
                    return@withLock
                }
                runCatching { effects.playBeepAndAwait() }
                if (!announcementGate.shouldContinueSpeech() || InternetConnectivity.online.value) {
                    return@withLock
                }
                speaking = true
                speechSpeaker.speakNow(connectionNotification.speechText)
                speaking = false
            }
        } finally {
            if (speaking) {
                speechSpeaker.stop()
            }
        }
    }
    val timerState by timerController.state.collectAsState()
    val alarmState by timerController.alarmState.collectAsState()
    val presentation by notifications.presentation.collectAsState()
    val activeNotification = presentation?.notification
    val queueProgress = presentation?.queueProgress
    val history by notifications.history.collectAsState()
    val operations by transitMonitor.operations.collectAsState()
    val transitLastFetchedAt by transitMonitor.lastFetchedAt.collectAsState()
    val weatherLastFetchedAt by weatherRepository.lastFetchedAt.collectAsState()
    val earthquakeLastPolledAt by earthquakeController.lastPolledAt.collectAsState()
    val activeWeatherWarnings by weatherWarningController.activeWarnings.collectAsState()
    val weatherWarningLastFetchedAt by weatherWarningController.lastFetchedAt.collectAsState()
    val eewPhase by earthquakeController.phase.collectAsState()
    val weatherWarningPhase by weatherWarningController.phase.collectAsState()
    val weatherWarningCrisisUi by weatherWarningController.crisisUi.collectAsState()
    val forceCloseMenuToken by earthquakeController.forceCloseMenu.collectAsState()
    val weatherWarningForceCloseToken by weatherWarningController.forceCloseMenu.collectAsState()
    val awayAlertActive by awayAlertController.active.collectAsState()
    val awayForceCloseToken by awayAlertController.forceCloseMenu.collectAsState()
    AwayAlertLockTask(active = awayAlertActive)
    AwayAlertVolumeLock(active = awayAlertActive)

    var showMenu by remember { mutableStateOf(false) }
    var openMenuOnMedia by remember { mutableStateOf(false) }
    LaunchedEffect(timerController) {
        combine(timerController.state, timerController.alarmState) { timer, alarm ->
            timer.phase == TimerPhase.Ringing || alarm.phase == AlarmPhase.Ringing
        }.collect { ringing ->
            if (ringing) {
                showMenu = false
            }
        }
    }
    var soundTestOpen by remember { mutableStateOf(false) }
    var interactionToken by remember { mutableIntStateOf(0) }
    var lastInteractionAtMillis by remember { mutableLongStateOf(0L) }
    var splitTestCursor by remember { mutableIntStateOf(0) }
    val splitTestScope = rememberCoroutineScope()
    LaunchedEffect(forceCloseMenuToken, weatherWarningForceCloseToken, awayForceCloseToken) {
        if (forceCloseMenuToken > 0 || weatherWarningForceCloseToken > 0 || awayForceCloseToken > 0) {
            showMenu = false
        }
    }
    LaunchedEffect(showMenu, interactionToken, soundTestOpen, eewPhase, weatherWarningPhase, awayAlertActive) {
        if (!showMenu ||
            soundTestOpen ||
            eewPhase == EarthquakeAlertPhase.FullScreen ||
            weatherWarningPhase == WeatherWarningAlertPhase.FullScreen ||
            awayAlertActive
        ) {
            return@LaunchedEffect
        }
        delay(MenuIdleTimeoutMillis)
        showMenu = false
    }

    fun postSplitTestNotification() {
        val scenario = SplitTestScenarios[splitTestCursor % SplitTestScenarios.size]
        splitTestCursor += 1
        notifications.post(
            category = NotificationCategoryTransit,
            title = scenario.line.displayName,
            body = "${scenario.statusLabel}\n${scenario.detail}",
            speechText = "鉄道運行情報のお知らせです。${scenario.line.displayName}は、${scenario.detail}",
            statusMark = scenario.statusMark,
            iconResIds = listOf(scenario.line.iconResId),
            alert = true,
        )
    }

    val eewFullScreen = eewPhase == EarthquakeAlertPhase.FullScreen
    val weatherWarningFullScreen = weatherWarningPhase == WeatherWarningAlertPhase.FullScreen
    val crisisFullScreen = eewFullScreen || weatherWarningFullScreen || awayAlertActive
    LaunchedEffect(soundTestOpen, crisisFullScreen) {
        timerController.setPlaybackBlocked(soundTestOpen || crisisFullScreen)
    }
    val nightBrightHold = lastInteractionAtMillis > 0L &&
        System.currentTimeMillis() - lastInteractionAtMillis < MenuIdleTimeoutMillis
    val deviceNowPlaying by systemMedia.deviceNowPlaying.collectAsState()
    NightModeBrightness(
        enabled = nightModeSettings.enabled,
        brightness = nightModeSettings.brightness,
        now = now,
        notificationVisible = activeNotification != null,
        crisisFullScreen = crisisFullScreen,
        holdBright = showMenu || nightBrightHold,
        mediaPlaying = deviceNowPlaying != null || spotifyPlaying.value,
        previewRatio = nightModeSettings.brightnessPreview,
    )
    val showRegularBanner = !showMenu && !crisisFullScreen && activeNotification != null
    val connectionOnClock = !online && !crisisFullScreen && activeNotification == null
    val showConnectionBanner = !showMenu && connectionOnClock
    val showRightPanel = showRegularBanner || showConnectionBanner
    // メニュー中も接続エラーの開き幅は保ち、戻ったときに時計を横へ押さない
    val bannerReveal by animateFloatAsState(
        targetValue = if (showRightPanel || (showMenu && connectionOnClock)) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "bannerReveal",
    )

    SmartDisplayTheme {
        ImmersiveDisplay()
        Box(modifier = Modifier.fillMaxSize()) {
        if (!crisisFullScreen) {
            if (showMenu) {
                MenuScreen(
                        now = now,
                        jihouSettings = jihouSettings,
                        notificationAnnounceSettings = notificationAnnounceSettings,
                        nightModeSettings = nightModeSettings,
                        calendarSettings = calendarSettings,
                        earthquakeSettings = earthquakeSettings,
                        awayAlertSettings = awayAlertSettings,
                        awayAlerts = awayAlertController.archive,
                        awayAlertController = awayAlertController,
                        chimeController = chimeController,
                        announcementGate = announcementGate,
                        notifications = notifications,
                        history = history,
                        operations = operations,
                        weatherRepository = weatherRepository,
                        weatherLastFetchedAt = weatherLastFetchedAt,
                        earthquakeLastPolledAt = earthquakeLastPolledAt,
                        transitLastFetchedAt = transitLastFetchedAt,
                        activeWeatherWarnings = activeWeatherWarnings,
                        weatherWarningLastFetchedAt = weatherWarningLastFetchedAt,
                        trainTimetableRepository = trainTimetableRepository,
                        busTimetableRepository = busTimetableRepository,
                        memorialRepository = memorialRepository,
                        busReminderScheduler = busReminderScheduler,
                        busScheduledTrips = busScheduledTrips,
                        onRefreshTransit = {
                            transitMonitor.requestRefresh { _ ->
                                if (InternetConnectivity.hasInternet()) {
                                    playBeep()
                                }
                            }
                        },
                        onScheduleSplitTest = {
                            postSplitTestNotification()
                        },
                        onCalendarAnnounceTest = { kind ->
                            splitTestScope.launch {
                                calendarAnnouncer.postAnnouncement(kind)
                            }
                        },
                        onCalendarSoonTest = {
                            calendarSoonMonitor.postTest()
                        },
                        onWeatherAnnounceTest = { kind ->
                            splitTestScope.launch {
                                weatherAnnouncer.postAnnouncement(
                                    kind,
                                    includeIndoorOutdoor = true,
                                )
                            }
                        },
                        onStepsAnnounceTest = {
                            splitTestScope.launch {
                                stepsAnnouncer.postAnnouncement()
                            }
                        },
                        onMemorialAnnounceTest = {
                            splitTestScope.launch {
                                memorialAnnouncer.postAnnouncement()
                            }
                        },
                        onQuoteAnnounceTest = {
                            splitTestScope.launch {
                                quoteAnnouncer.postAnnouncement()
                            }
                        },
                        onExpirationAnnounceTest = {
                            splitTestScope.launch {
                                expirationAnnouncer.postCloser()
                            }
                        },
                        onNoonNoticeTest = {
                            showMenu = false
                            splitTestScope.launch {
                                val now = LocalDateTime.now()
                                memorialAnnouncer.postAnnouncement(now.toLocalDate())
                                weatherAnnouncer.postAnnouncement(
                                    WeatherAnnounceKind.RestOfToday,
                                    now,
                                    includeIndoorOutdoor = true,
                                )
                                calendarAnnouncer.postAnnouncement(CalendarAnnounceKind.RestOfToday)
                                expirationAnnouncer.postCloser(now.toLocalDate())
                                quoteAnnouncer.postAnnouncement()
                            }
                        },
                        onLateNoticeTest = {
                            showMenu = false
                            splitTestScope.launch {
                                val now = LocalDateTime.now()
                                stepsAnnouncer.postAnnouncement(now)
                                weatherAnnouncer.postAnnouncement(
                                    WeatherAnnounceKind.Tomorrow,
                                    now,
                                    includeIndoorOutdoor = true,
                                )
                                calendarAnnouncer.postAnnouncement(CalendarAnnounceKind.Tomorrow)
                                garbageAnnouncer.post(now.toLocalDate().plusDays(1), tomorrow = true)
                                expirationAnnouncer.postCloser(now.toLocalDate())
                            }
                        },
                        onSunsetTest = {
                            showMenu = false
                            sunsetAnnouncer.playTest()
                        },
                        onMorningBriefTest = {
                            showMenu = false
                            morningBrief.playTest()
                        },
                        onEewTest = {
                            earthquakeController.triggerEewTest()
                        },
                        onEarthquakeIntensityTest = { scale ->
                            earthquakeController.triggerIntensityTest(scale)
                        },
                        onEarthquakeIntensityUpdateTest = {
                            earthquakeController.triggerIntensityUpdateTest()
                        },
                        onWeatherWarningTest = { severity ->
                            weatherWarningController.triggerTestIssue(severity)
                        },
                        onWeatherWarningCancelTest = { severity ->
                            weatherWarningController.triggerTestCancel(severity)
                        },
                        onPreviewEewVolume = { ratio ->
                            splitTestScope.launch {
                                MusicVolumeBoost.raise(context, ratio)
                                try {
                                    announcementGate.withPriorityLock(
                                        interrupt = { speechSpeaker.stop() },
                                    ) {
                                        speechSpeaker.speakNow("この音量で警告案内をします")
                                    }
                                } finally {
                                    MusicVolumeBoost.restore(context)
                                }
                            }
                        },
                        onPreviewAwayVolume = { ratio ->
                            splitTestScope.launch {
                                MusicVolumeBoost.raise(context, ratio)
                                try {
                                    announcementGate.withPriorityLock(
                                        interrupt = { speechSpeaker.stop() },
                                    ) {
                                        speechSpeaker.speakNow("この音量で外出アラートを案内します")
                                    }
                                } finally {
                                    MusicVolumeBoost.restore(context)
                                }
                            }
                        },
                        onBack = { showMenu = false },
                        onInteraction = {
                            lastInteractionAtMillis = System.currentTimeMillis()
                            interactionToken++
                        },
                        onSoundTestOpen = { soundTestOpen = it },
                        onTapSound = playTap,
                        onErrorSound = playBeep,
                        startOnMedia = openMenuOnMedia,
                        timer = timerController,
                        onTimerStarted = { showMenu = false },
                        modifier = Modifier.fillMaxSize(),
                    )
            } else {
                val nowPlaying = rememberClockNowPlaying(systemMedia)
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val bannerWidth = maxWidth * 0.5f
                val bannerWidthPx = with(density) { bannerWidth.toPx() }
                val contentWidth = maxWidth - bannerWidth * bannerReveal
                val panelNotification = when {
                    showRegularBanner -> activeNotification
                    showConnectionBanner -> connectionNotification
                    else -> null
                }
                    val timerLabel = clockStatusLabel(timerState, alarmState, now)
                    val todaySun = remember(weatherLastFetchedAt, now.toLocalDate()) {
                        weatherRepository.cachedForecast(now)
                            ?.daily
                            ?.firstOrNull { it.date == now.toLocalDate() }
                    }
                    ClockFace(
                        dateTime = now,
                        nowPlayingTitle = nowPlaying?.title,
                        nowPlayingArtwork = nowPlaying?.artwork,
                        nowPlayingProgress = nowPlaying?.progress,
                        timerLabel = timerLabel,
                        sunrise = todaySun?.sunrise,
                        sunset = todaySun?.sunset,
                        onNowPlayingClick = {
                            playTap()
                            lastInteractionAtMillis = System.currentTimeMillis()
                            interactionToken = 0
                            openMenuOnMedia = true
                            showMenu = true
                        },
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight()
                            .background(Color.Black)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    playTap()
                                    if (showRegularBanner) {
                                        notifications.dismissActiveManually()
                                    }
                                    lastInteractionAtMillis = System.currentTimeMillis()
                                    interactionToken = 0
                                    openMenuOnMedia = false
                                    showMenu = true
                                },
                            ),
                    )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(bannerWidth)
                        .fillMaxHeight()
                        .offset {
                            IntOffset(
                                x = ((1f - bannerReveal) * bannerWidthPx).roundToInt(),
                                y = 0,
                            )
                        }
                        .clipToBounds(),
                ) {
                    val notification = panelNotification
                    if (notification != null && bannerReveal > 0.001f) {
                        NotificationBanner(
                            notification = notification,
                            onDismiss = {
                                if (!showRegularBanner) {
                                    return@NotificationBanner
                                }
                                playTap()
                                notifications.dismissActiveManually()
                            },
                            onMuteToday = {
                                playTap()
                                val category = notification.category
                                notifications.muteTodayAndDismiss(category)
                                if (category == NotificationCategoryTransit) {
                                    transitMonitor.setPollingSuppressed(true)
                                }
                            },
                            queueProgress = if (showRegularBanner) queueProgress else null,
                            dismissible = showRegularBanner,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                }
            }
        }
        if (eewFullScreen) {
            EewFullscreen()
        } else if (weatherWarningFullScreen) {
            WeatherWarningFullscreen(
                title = weatherWarningCrisisUi.title,
                message = weatherWarningCrisisUi.message,
            )
        } else if (awayAlertActive) {
            AwayAlertScreen(
                controller = awayAlertController,
                onTapSound = playTap,
                onBeep = playBeep,
            )
        }
        AppToastHost()
        }
    }
}

/**
 * 外出アラート中は画面を固定し、ホームやアプリ切り替えで離れられないようにする。
 * 固定を解いて戻ってきても、アラートが続いているあいだは再度固定する。
 */
@Composable
private fun AwayAlertLockTask(active: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(activity, active) {
        val host = activity
        val owner = host as? LifecycleOwner
        if (host == null || owner == null || !active) {
            onDispose { }
        } else {
            fun pin() {
                runCatching { host.startLockTask() }
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    pin()
                }
            }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                pin()
            }
            onDispose {
                owner.lifecycle.removeObserver(observer)
                runCatching { host.stopLockTask() }
            }
        }
    }
}

@Composable
private fun ImmersiveDisplay() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        hideSystemBars(activity)
        onDispose {
            showSystemBars(activity)
        }
    }
}

private data class ClockNowPlaying(
    val title: String,
    val artwork: ImageBitmap?,
    val progress: ClockProgress,
)

private data class ClockProgress(
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val speed: Float = 1f,
    val anchorElapsed: Long = 0L,
) {
    fun display(nowElapsed: Long): ClockProgressDisplay {
        if (positionMs < 0L) {
            return ClockProgressDisplay(fraction = 1f, label = UnknownProgressLabel)
        }
        val delta = if (playing && anchorElapsed > 0L) {
            ((nowElapsed - anchorElapsed).coerceAtLeast(0L) * speed.toDouble()).toLong()
        } else {
            0L
        }
        val advanced = (positionMs + delta).coerceAtLeast(0L)
        if (durationMs <= 0L) {
            return ClockProgressDisplay(
                fraction = 1f,
                label = "${formatProgressMs(advanced)} / --:--",
            )
        }
        val position = advanced.coerceIn(0L, durationMs)
        return ClockProgressDisplay(
            fraction = position.toFloat() / durationMs.toFloat(),
            label = "${formatProgressMs(position)} / ${formatProgressMs(durationMs)}",
        )
    }
}

private data class ClockProgressDisplay(
    val fraction: Float,
    val label: String,
)

private val UnknownClockProgress = ClockProgress(
    positionMs = -1L,
    durationMs = 0L,
    playing = false,
)

private const val UnknownProgressLabel = "--:-- / --:--"

@Composable
private fun rememberClockNowPlaying(hub: SystemMediaHub): ClockNowPlaying? {
    val context = LocalContext.current.applicationContext
    val local by hub.deviceNowPlaying.collectAsState()
    val connected by SpotifySession.connected.collectAsState()
    val player = remember(context) { SpotifyPlayer(context) }
    var spotifyTitle by remember { mutableStateOf<String?>(null) }
    var artworkUrl by remember { mutableStateOf<String?>(null) }
    var spotifyProgress by remember { mutableStateOf(UnknownClockProgress) }
    val artwork = rememberSpotifyArtwork(player, artworkUrl)
    val playingHere = local
    val localTitle = playingHere?.title?.takeIf {
        it.isNotBlank() && (playingHere.playing || playingHere.remote)
    }
    LaunchedEffect(connected, localTitle) {
        if (localTitle != null || !connected) {
            spotifyTitle = null
            artworkUrl = null
            spotifyProgress = UnknownClockProgress
            return@LaunchedEffect
        }
        while (true) {
            try {
                val playback = player.playback()
                if (playback == null || !playback.playing || playback.title.isBlank()) {
                    spotifyTitle = null
                    artworkUrl = null
                    spotifyProgress = UnknownClockProgress
                } else {
                    spotifyTitle = playback.title
                    artworkUrl = playback.artworkUrl
                    spotifyProgress = ClockProgress(
                        positionMs = playback.progressMs,
                        durationMs = playback.durationMs,
                        playing = true,
                        anchorElapsed = SystemClock.elapsedRealtime(),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
            delay(5_000)
        }
    }
    if (localTitle != null) {
        return ClockNowPlaying(
            title = localTitle,
            artwork = playingHere.artwork,
            progress = playingHere.clockProgress(),
        )
    }
    val current = spotifyTitle ?: return null
    return ClockNowPlaying(title = current, artwork = artwork, progress = spotifyProgress)
}

@Composable
private fun ClockFace(
    dateTime: LocalDateTime,
    nowPlayingTitle: String? = null,
    nowPlayingArtwork: ImageBitmap? = null,
    nowPlayingProgress: ClockProgress? = null,
    timerLabel: String? = null,
    sunrise: LocalTime? = null,
    sunset: LocalTime? = null,
    onNowPlayingClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val locale = Locale.getDefault()
    val timeFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    }
    val dateFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    }
    val dayCaption = remember(dateTime.toLocalDate()) {
        holidayAndSeasonalLabel(dateTime.toLocalDate())
    }

    BoxWithConstraints(modifier) {
        val timeSize = (maxWidth.value * 0.28f).coerceIn(84f, 220f)
        val dateSize = (maxWidth.value * 0.06f).coerceIn(26f, 52f)
        val trackSize = (maxWidth.value * 0.028f).coerceIn(16f, 22f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    ClockHourMinute(
                        time = dateTime.format(timeFormatter),
                        style = clockStyle(timeSize, FontWeight.Light),
                        color = Color.White,
                    )
                    BaselineText(
                        text = dateTime.second.toString().padStart(2, '0'),
                        style = clockStyle(timeSize * 0.38f, FontWeight.Light),
                        color = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
                Text(
                    text = dateTime.format(dateFormatter),
                    modifier = Modifier.padding(top = 28.dp),
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = clockStyle(dateSize, FontWeight.Normal),
                )
                // 高さに含めない。日付の直下に出し、時計と日付は上下中央のままにする。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(0.dp)
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (dayCaption != null) {
                            Text(
                                text = dayCaption,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth(),
                                color = Color.White.copy(alpha = 0.62f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = clockStyle((dateSize * 0.52f).coerceIn(16f, 26f), FontWeight.Normal),
                            )
                        }
                        val sunLead = sunClockLead(dateTime, sunrise, sunset)
                        val sunTime = when (sunLead) {
                            SunClockLead.Sunrise -> sunrise
                            SunClockLead.Sunset -> sunset
                            null -> null
                        }
                        if (sunLead != null && sunTime != null) {
                            SunTimeLine(
                                rising = sunLead == SunClockLead.Sunrise,
                                time = sunTime,
                                color = Color.White.copy(alpha = 0.34f),
                                textStyle = clockStyle((dateSize * 0.40f).coerceIn(14f, 20f), FontWeight.Normal),
                                iconHeight = (dateSize * 0.64f).coerceIn(24f, 32f).dp,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (!timerLabel.isNullOrBlank()) {
                            Text(
                                text = timerLabel,
                                modifier = Modifier.padding(top = 16.dp),
                                color = Color.White.copy(alpha = 0.38f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = clockStyle((dateSize * 0.46f).coerceIn(15f, 22f), FontWeight.Normal),
                            )
                        }
                    }
                }
            }
        }
        val trackTitle = nowPlayingTitle?.takeIf { it.isNotBlank() }
        if (trackTitle != null) {
            val artSize = (trackSize * 1.6f).coerceIn(26f, 36f).dp
            val progress = (nowPlayingProgress ?: UnknownClockProgress).display(SystemClock.elapsedRealtime())
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNowPlayingClick,
                    )
                    .padding(start = 28.dp, end = 28.dp, bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtworkFrame(
                        artwork = nowPlayingArtwork,
                        modifier = Modifier.size(artSize),
                        cornerRadius = 4.dp,
                    )
                    Text(
                        text = trackTitle,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .basicMarquee(iterations = Int.MAX_VALUE),
                        color = Color.White.copy(alpha = 0.4f),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = clockStyle(trackSize, FontWeight.Normal),
                    )
                }
                Text(
                    text = progress.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = Color.White.copy(alpha = 0.28f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    style = clockStyle((trackSize * 0.72f).coerceIn(12f, 15f), FontWeight.Normal),
                )
                PlaybackLine(
                    fraction = progress.fraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp)
                        .height(2.dp),
                )
            }
        }
    }
}

@Composable
private fun RowScope.ClockHourMinute(
    time: String,
    style: TextStyle,
    color: Color,
) {
    val colonAt = time.indexOf(':').takeIf { it >= 0 } ?: time.indexOf('：')
    if (colonAt < 0) {
        BaselineText(time, style, color)
        return
    }
    // コロンの字形は数字より下に寄る。インクの上下中央を数字に合わせる。
    val lift = rememberColonLiftPx(style, time.substring(colonAt, colonAt + 1))
    if (colonAt > 0) {
        BaselineText(time.substring(0, colonAt), style, color)
    }
    Text(
        text = time.substring(colonAt, colonAt + 1),
        modifier = Modifier
            .alignByBaseline()
            .graphicsLayer { translationY = -lift },
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = style,
    )
    if (colonAt < time.lastIndex) {
        BaselineText(time.substring(colonAt + 1), style, color)
    }
}

@Composable
private fun rememberColonLiftPx(style: TextStyle, separator: String): Float {
    val resolver = LocalFontFamilyResolver.current
    val density = LocalDensity.current
    val typeface = resolver.resolve(
        fontFamily = style.fontFamily,
        fontWeight = style.fontWeight ?: FontWeight.Normal,
        fontStyle = style.fontStyle ?: FontStyle.Normal,
        fontSynthesis = style.fontSynthesis ?: FontSynthesis.All,
    ).value as Typeface
    val fontSizePx = with(density) { style.fontSize.toPx() }
    val features = style.fontFeatureSettings
    return remember(typeface, fontSizePx, separator, features) {
        colonInkLiftPx(typeface, fontSizePx, separator, features)
    }
}

private fun colonInkLiftPx(
    typeface: Typeface,
    fontSizePx: Float,
    separator: String,
    fontFeatureSettings: String?,
): Float {
    if (fontSizePx <= 0f || separator.isEmpty()) return 0f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        this.typeface = typeface
        this.fontFeatureSettings = fontFeatureSettings
    }
    val digit = Rect()
    val colon = Rect()
    paint.getTextBounds("0", 0, 1, digit)
    paint.getTextBounds(separator, 0, separator.length, colon)
    if (digit.height() == 0 || colon.height() == 0) return 0f
    val digitCenter = (digit.top + digit.bottom) / 2f
    val colonCenter = (colon.top + colon.bottom) / 2f
    return colonCenter - digitCenter
}

@Composable
private fun RowScope.BaselineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.alignByBaseline(),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        style = style,
    )
}

private fun SystemMedia.clockProgress(): ClockProgress {
    return ClockProgress(
        positionMs = positionMs,
        durationMs = durationMs,
        playing = playing,
        speed = if (playbackSpeed > 0f) playbackSpeed else 1f,
        anchorElapsed = positionUpdatedAt,
    )
}

@Composable
private fun PlaybackLine(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val played = fraction.coerceIn(0f, 1f)
    Canvas(modifier) {
        val y = size.height / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = size.height,
            cap = StrokeCap.Butt,
        )
        val playedWidth = size.width * played
        if (playedWidth > 0f) {
            drawLine(
                color = Color.White.copy(alpha = 0.36f),
                start = Offset(0f, y),
                end = Offset(playedWidth, y),
                strokeWidth = size.height,
                cap = StrokeCap.Butt,
            )
        }
    }
}

private fun formatProgressMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val seconds = total % 60
    val hours = total / 3600
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, (total / 60) % 60, seconds)
    } else {
        "%02d:%02d".format(total / 60, seconds)
    }
}

@Composable
private fun SunTimeLine(
    rising: Boolean,
    time: LocalTime,
    color: Color,
    textStyle: TextStyle,
    iconHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val label = time.format(SunClockTimeFormatter)
    val name = if (rising) "日の出" else "日の入り"
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$name $label"
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SunEventIcon(
            rising = rising,
            color = color,
            modifier = Modifier.height(iconHeight),
        )
        Text(
            text = label,
            color = color,
            maxLines = 1,
            style = textStyle,
        )
    }
}

private val SunClockTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun clockStyle(size: Float, weight: FontWeight): TextStyle {
    return TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
        fontFeatureSettings = "tnum",
    )
}

private fun hideSystemBars(activity: Activity) {
    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}

private fun showSystemBars(activity: Activity) {
    val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    controller.show(WindowInsetsCompat.Type.systemBars())
}

private data class SplitTestScenario(
    val line: TrainLine,
    val statusLabel: String,
    val detail: String,
    val statusMark: NotificationStatusMark,
)

private val SplitTestScenarios = listOf(
    SplitTestScenario(
        line = TrainLine.JrChuoRapid,
        statusLabel = "運転見合わせ",
        detail = "四ツ谷駅での人身事故のため運転を見合わせています。振替輸送を実施中です。",
        statusMark = NotificationStatusMark.Stopped,
    ),
    SplitTestScenario(
        line = TrainLine.MetroMarunouchi,
        statusLabel = "遅延",
        detail = "新宿駅での信号故障のため遅延が発生しています。",
        statusMark = NotificationStatusMark.Caution,
    ),
    SplitTestScenario(
        line = TrainLine.JrChuoRapid,
        statusLabel = "遅延",
        detail = "高円寺駅でのドア故障の影響で、各駅で遅延が発生しています。",
        statusMark = NotificationStatusMark.Caution,
    ),
    SplitTestScenario(
        line = TrainLine.MetroMarunouchi,
        statusLabel = "運転見合わせ",
        detail = "淡路町駅でのお客様救護のため、全線で運転を見合わせています。",
        statusMark = NotificationStatusMark.Stopped,
    ),
    SplitTestScenario(
        line = TrainLine.JrChuoRapid,
        statusLabel = "運転再開",
        detail = "四ツ谷駅での支障が解消し、運転を再開しました。遅れが出ています。",
        statusMark = NotificationStatusMark.Caution,
    ),
    SplitTestScenario(
        line = TrainLine.MetroMarunouchi,
        statusLabel = "平常運転",
        detail = "先ほどお知らせした遅延は解消し、平常どおり運転しています。",
        statusMark = NotificationStatusMark.Normal,
    ),
    SplitTestScenario(
        line = TrainLine.JrChuoRapid,
        statusLabel = "遅延",
        detail = "大雨の影響で速度を落として運転しているため、遅延が発生しています。",
        statusMark = NotificationStatusMark.Caution,
    ),
    SplitTestScenario(
        line = TrainLine.MetroMarunouchi,
        statusLabel = "一部列車運休",
        detail = "車両点検のため、一部列車が運休しています。ご利用の際はご注意ください。",
        statusMark = NotificationStatusMark.Caution,
    ),
)

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun ClockFacePreview() {
    SmartDisplayTheme {
        ClockFace(
            dateTime = LocalDateTime.of(2026, 11, 12, 21, 4, 5),
            nowPlayingTitle = "夜に駆ける",
            nowPlayingProgress = ClockProgress(
                positionMs = 83_000L,
                durationMs = 249_000L,
                playing = false,
            ),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        )
    }
}
