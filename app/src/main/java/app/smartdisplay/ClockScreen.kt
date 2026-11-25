package app.smartdisplay

import android.app.Activity
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.smartdisplay.jihou.holidayAndSeasonalLabel
import app.smartdisplay.media.ArtworkFrame
import app.smartdisplay.media.SystemMedia
import app.smartdisplay.media.SystemMediaHub
import app.smartdisplay.media.rememberSystemMedia
import app.smartdisplay.notification.AppNotification
import app.smartdisplay.notification.NotificationBanner
import app.smartdisplay.notification.NotificationCategoryGeneral
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.sun.SunClockLead
import app.smartdisplay.sun.SunEventIcon
import app.smartdisplay.sun.sunClockLead
import app.smartdisplay.sun.sunTimesFor
import app.smartdisplay.ui.SmartDisplayTheme
import app.smartdisplay.util.AppToastHost
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.millisUntilNextCacheMaintenance
import app.smartdisplay.util.pruneAppCaches
import app.smartdisplay.util.rememberDeviceLocationHub
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

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
    val runtime = remember { KeepAliveRuntime.from(context).ensureStarted() }
    rememberChargingSupplyAlert()
    rememberLinkConnectionAlerts()
    DisposableEffect(context) {
        InternetConnectivity.start(context)
        onDispose { InternetConnectivity.stop() }
    }
    val online by InternetConnectivity.online.collectAsState()
    val soundPresenceGate = runtime.presenceGate
    val effects = runtime.effects
    val playTap: () -> Unit = remember(effects) { { effects.playTap() } }
    val playBeep: () -> Unit = remember(effects) { { effects.playBeep() } }
    val speechSpeaker = runtime.speechSpeaker
    val announcementGate = runtime.announcementGate
    val jihouSettings = runtime.jihouSettings
    val notificationAnnounceSettings = runtime.notificationAnnounceSettings
    val systemMedia = rememberSystemMedia()
    StartupPermissionRequests(systemMedia)
    val chimeController = runtime.chimeController
    LaunchedEffect(chimeController, jihouSettings.enabled) {
        chimeController.setEnabled(jihouSettings.enabled)
    }
    val notifications = runtime.notifications
    val deviceLocation = rememberDeviceLocationHub()
    val deviceCoordinates by deviceLocation.coordinates.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextCacheMaintenance())
            pruneAppCaches()
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
                if (!soundPresenceGate.mayPlayNow()) {
                    return@withLock
                }
                runCatching { effects.playBeepAndAwait() }
                if (!announcementGate.shouldContinueSpeech() || InternetConnectivity.online.value) {
                    return@withLock
                }
                if (!soundPresenceGate.mayPlayNow()) {
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
    val presentation by notifications.presentation.collectAsState()
    val activeNotification = presentation?.notification
    val queueProgress = presentation?.queueProgress

    var showMenu by remember { mutableStateOf(false) }
    var openMenuOnMedia by remember { mutableStateOf(false) }
    var soundTestOpen by remember { mutableStateOf(false) }
    var interactionToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(showMenu, interactionToken, soundTestOpen) {
        if (!showMenu || soundTestOpen) {
            return@LaunchedEffect
        }
        delay(MenuIdleTimeoutMillis)
        showMenu = false
    }

    val showRegularBanner = !showMenu && activeNotification != null
    val connectionOnClock = !online && activeNotification == null
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
            if (showMenu) {
                MenuScreen(
                    now = now,
                    jihouSettings = jihouSettings,
                    notificationAnnounceSettings = notificationAnnounceSettings,
                    soundPresenceGate = soundPresenceGate,
                    chimeController = chimeController,
                    announcementGate = announcementGate,
                    notifications = notifications,
                    onBack = { showMenu = false },
                    onInteraction = { interactionToken++ },
                    onSoundTestOpen = { soundTestOpen = it },
                    onTapSound = playTap,
                    onErrorSound = playBeep,
                    startOnMedia = openMenuOnMedia,
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
                    val todaySun = remember(now.toLocalDate(), deviceCoordinates) {
                        sunTimesFor(
                            date = now.toLocalDate(),
                            latitudeDegrees = deviceCoordinates?.latitude,
                            longitudeDegrees = deviceCoordinates?.longitude,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight(),
                    ) {
                        ClockFace(
                            dateTime = now,
                            nowPlayingTitle = nowPlaying?.title,
                            nowPlayingArtwork = nowPlaying?.artwork,
                            nowPlayingProgress = nowPlaying?.progress,
                            sunrise = todaySun?.sunrise,
                            sunset = todaySun?.sunset,
                            onNowPlayingClick = {
                                playTap()
                                interactionToken = 0
                                openMenuOnMedia = true
                                showMenu = true
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        playTap()
                                        if (showRegularBanner) {
                                            notifications.dismissActiveManually()
                                        }
                                        interactionToken = 0
                                        openMenuOnMedia = false
                                        showMenu = true
                                    },
                                ),
                        )
                        if (!soundPresenceGate.mayPlay) {
                            SoundMutedIndicator(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(start = 18.dp, top = 16.dp)
                                    .size(44.dp)
                                    .clearAndSetSemantics {
                                        contentDescription = "音は鳴りません"
                                    },
                            )
                        }
                    }
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
                                queueProgress = if (showRegularBanner) queueProgress else null,
                                dismissible = showRegularBanner,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            AppToastHost()
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

/** 外出抑制で音が出ないときの控えめな表示。 */
@Composable
private fun SoundMutedIndicator(
    modifier: Modifier = Modifier,
) {
    val color = Color.White.copy(alpha = 0.28f)
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = size.minDimension * 0.09f,
            cap = StrokeCap.Round,
        )
        val bodyLeft = size.width * 0.12f
        val bodyRight = size.width * 0.38f
        val bodyTop = size.height * 0.34f
        val bodyBottom = size.height * 0.66f
        val coneTipX = size.width * 0.58f
        val coneTop = size.height * 0.18f
        val coneBottom = size.height * 0.82f
        val speaker = Path().apply {
            moveTo(bodyLeft, bodyTop)
            lineTo(bodyRight, bodyTop)
            lineTo(coneTipX, coneTop)
            lineTo(coneTipX, coneBottom)
            lineTo(bodyRight, bodyBottom)
            lineTo(bodyLeft, bodyBottom)
            close()
        }
        drawPath(path = speaker, color = color)
        val slashPad = size.minDimension * 0.12f
        drawLine(
            color = color,
            start = Offset(slashPad, size.height - slashPad),
            end = Offset(size.width - slashPad, slashPad),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
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
    val local by hub.deviceNowPlaying.collectAsState()
    val playing = local ?: return null
    val title = playing.title.takeIf {
        it.isNotBlank() && (playing.playing || playing.remote)
    } ?: return null
    return ClockNowPlaying(
        title = title,
        artwork = playing.artwork,
        progress = playing.clockProgress(),
    )
}

@Composable
private fun ClockFace(
    dateTime: LocalDateTime,
    nowPlayingTitle: String? = null,
    nowPlayingArtwork: ImageBitmap? = null,
    nowPlayingProgress: ClockProgress? = null,
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
