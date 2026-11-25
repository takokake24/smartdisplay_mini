package app.smartdisplay.notification

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.earthquake.intensityDisplayGlyph
import app.smartdisplay.expiration.ExpirationNotificationContent
import app.smartdisplay.memorial.MemorialNotificationList
import app.smartdisplay.steps.StepsAnnouncementContent
import app.smartdisplay.weather.IndoorOutdoorClimate
import app.smartdisplay.weather.IndoorOutdoorFailureLabel
import app.smartdisplay.weather.IndoorOutdoorNotice
import app.smartdisplay.weather.WeatherIcon
import app.smartdisplay.weather.WeatherSnapshot
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.cos
import kotlin.math.sin
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun NotificationBanner(
    notification: AppNotification,
    onDismiss: () -> Unit,
    onMuteToday: () -> Unit,
    muteLabel: String = "今日は通知しない",
    queueProgress: NotificationQueueProgress? = null,
    dismissible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (notification.isEew || notification.isWeatherWarningCritical) {
        EewCrisisBanner(
            notification = notification,
            onDismiss = onDismiss,
            queueProgress = queueProgress,
            modifier = modifier,
        )
        return
    }
    val ringingClock = (notification.category == NotificationCategoryTimer ||
        notification.category == NotificationCategoryAlarm) &&
        notification.holdUntilDismiss
    val showHero = ringingClock ||
        notification.appIcon != null ||
        notification.iconResIds.isNotEmpty() ||
        notification.statusMark != null
    Column(
        modifier
            .fillMaxHeight()
            .background(Color.White)
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val appIcon = notification.appIcon
            if (ringingClock) {
                ShakingClock(modifier = Modifier.align(Alignment.TopCenter))
            } else if (appIcon != null) {
                DeviceAppIcon(
                    icon = appIcon,
                    contentDescription = notification.sourceLabel.ifBlank { null },
                )
            } else if (showHero) {
                LineStatusHero(
                    iconResIds = notification.iconResIds,
                    statusMark = notification.statusMark,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            if (queueProgress != null || dismissible) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (queueProgress != null) {
                        Text(
                            text = queueProgress.label,
                            color = Color(0xFF444444),
                            fontSize = 22.sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (dismissible) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0x11000000))
                                .clickable(onClick = onDismiss),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "×",
                                color = Color(0xFF222222),
                                fontSize = 30.sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
        if (!notification.isSteps) {
            if (showHero) {
                Spacer(modifier = Modifier.height(28.dp))
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        if (notification.isWeatherWarning) {
            WarningTriangleMark(
                fillColor = Color(0xFFFFC107),
                strokeColor = Color(0xFF111111),
                bangColor = Color(0xFF111111),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                size = 96.dp,
            )
        }
        if (!notification.isSteps) {
            Text(
                text = notification.title,
                color = Color(0xFF111111),
                fontSize = 36.sp,
                lineHeight = 52.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = if (notification.isWeatherWarning) TextAlign.Center else TextAlign.Start,
                modifier = if (notification.isWeatherWarning) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                },
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
        when {
            notification.isSteps -> {
                StepsAnnouncementContent(
                    todaySteps = notification.stepsToday ?: 0L,
                    weekSteps = notification.stepsWeek ?: 0L,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            notification.isCalendar -> {
                CalendarNotificationContent(
                    notification = notification,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            notification.isMemorial -> {
                MemorialNotificationList(
                    names = notification.memorialNames,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            notification.isQuote -> {
                QuoteNotificationContent(
                    notification = notification,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            notification.isWeather -> {
                WeatherNotificationContent(
                    notification = notification,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            notification.isEarthquakeIntensity -> {
                EarthquakeIntensityContent(
                    notification = notification,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            notification.isExpiration -> {
                ExpirationNotificationContent(
                    items = notification.expirationItems,
                    fallbackBody = notification.body,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
            else -> {
                Text(
                    text = notification.body,
                    color = Color(0xFF333333),
                    fontSize = 26.sp,
                    lineHeight = 38.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (notification.isTransit || notification.isGarbage || notification.isCalendarSoon) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x22000000), RoundedCornerShape(18.dp))
                    .clickable(onClick = onMuteToday)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = muteLabel,
                    color = Color(0xFF222222),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if ((notification.category == NotificationCategoryTimer ||
                notification.category == NotificationCategoryAlarm) &&
            notification.holdUntilDismiss
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x22000000), RoundedCornerShape(18.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "停止",
                    color = Color(0xFF222222),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ShakingClock(modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "timerClock").animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 720
                0f at 0
                -9f at 45
                9f at 95
                -8f at 145
                8f at 195
                -4f at 245
                3f at 295
                0f at 370
                0f at 720
            },
        ),
        label = "timerClockAngle",
    )
    Canvas(
        modifier = modifier
            .size(84.dp)
            .graphicsLayer {
                rotationZ = angle
                transformOrigin = TransformOrigin.Center
            },
    ) {
        val color = Color(0xFF111111)
        val stroke = size.minDimension * 0.075f
        val radius = size.minDimension / 2f - stroke
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = stroke),
        )
        fun handTip(degrees: Float, length: Float): Offset {
            val radians = Math.toRadians(degrees.toDouble())
            return Offset(
                x = center.x + (sin(radians) * length).toFloat(),
                y = center.y - (cos(radians) * length).toFloat(),
            )
        }
        drawLine(
            color = color,
            start = center,
            end = handTip(degrees = -58f, length = radius * 0.48f),
            strokeWidth = stroke * 1.15f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = center,
            end = handTip(degrees = 48f, length = radius * 0.72f),
            strokeWidth = stroke * 0.85f,
            cap = StrokeCap.Round,
        )
        drawCircle(color = color, radius = stroke * 0.7f, center = center)
    }
}

@Composable
private fun EewCrisisBanner(
    notification: AppNotification,
    onDismiss: () -> Unit,
    queueProgress: NotificationQueueProgress?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF8B0000),
                        Color(0xFFB71C1C),
                        Color(0xFF4A0000),
                    ),
                ),
            )
            .padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (queueProgress != null) {
                Text(
                    text = queueProgress.label,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 22.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (notification.isWeatherWarningCritical) {
                WarningTriangleMark(
                    fillColor = Color(0xFFFFC107),
                    strokeColor = Color.White,
                    bangColor = Color(0xFF111111),
                    modifier = Modifier.padding(bottom = 22.dp),
                    size = 110.dp,
                )
            }
            Text(
                text = notification.title,
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = notification.body,
                color = Color(0xFFFFF59D),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 42.sp,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun WarningTriangleMark(
    fillColor: Color,
    strokeColor: Color,
    bangColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val pad = w * 0.06f
            val path = Path().apply {
                moveTo(w / 2f, pad)
                lineTo(w - pad, h - pad)
                lineTo(pad, h - pad)
                close()
            }
            drawPath(path = path, color = fillColor)
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(
                    width = w * 0.07f,
                    join = StrokeJoin.Round,
                    cap = StrokeCap.Round,
                ),
            )
            val bangWidth = w * 0.085f
            val bangTop = h * 0.32f
            val bangBottom = h * 0.62f
            drawLine(
                color = bangColor,
                start = Offset(w / 2f, bangTop),
                end = Offset(w / 2f, bangBottom),
                strokeWidth = bangWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = bangColor,
                radius = bangWidth * 0.55f,
                center = Offset(w / 2f, h * 0.74f),
            )
        }
    }
}

@Composable
private fun QuoteNotificationContent(
    notification: AppNotification,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = notification.body,
            color = Color(0xFF333333),
            fontSize = 26.sp,
            lineHeight = 40.sp,
        )
        val author = notification.quoteAuthor.trim()
        if (author.isNotEmpty()) {
            Text(
                text = author,
                color = Color(0xFF555555),
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun EarthquakeIntensityContent(
    notification: AppNotification,
    modifier: Modifier = Modifier,
) {
    val glyph = notification.earthquakeIntensityScale?.let(::intensityDisplayGlyph)
        ?: notification.body
    val occurredAt = notification.earthquakeOccurredAt?.let { time ->
        "${time.hour}:${time.minute.toString().padStart(2, '0')}"
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "震度",
            color = Color(0xFF444444),
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = glyph,
            color = Color(0xFFB71C1C),
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (occurredAt != null) {
            Text(
                text = "発生時刻：$occurredAt",
                color = Color(0xFF444444),
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun WeatherNotificationContent(
    notification: AppNotification,
    modifier: Modifier = Modifier,
) {
    val snapshot = notification.weatherSnapshot
    val indoorOutdoor = snapshot?.indoorOutdoor
    if (snapshot != null && indoorOutdoor != null) {
        Column(modifier = modifier.fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                WeatherSnapshotVisuals(snapshot)
            }
            IndoorOutdoorSection(
                notice = indoorOutdoor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
        }
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (snapshot != null) {
            WeatherSnapshotVisuals(snapshot)
        } else {
            Text(
                text = notification.body,
                color = Color(0xFF333333),
                fontSize = 24.sp,
                lineHeight = 36.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun WeatherSnapshotVisuals(snapshot: WeatherSnapshot) {
    val current = snapshot.current
    if (current != null) {
        Text(
            text = "現在",
            color = Color(0xFF666666),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        WeatherIcon(
            weatherCode = current.weatherCode,
            size = 88.dp,
        )
        Text(
            text = current.weatherText,
            color = Color(0xFF111111),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "${current.temperatureC.roundToInt()}℃",
            color = Color(0xFF222222),
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = snapshot.label,
            color = Color(0xFF666666),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    WeatherIcon(
        weatherCode = snapshot.weatherCode,
        size = if (current != null) 112.dp else 148.dp,
    )
    Text(
        text = snapshot.weatherText,
        color = Color(0xFF111111),
        fontSize = if (current != null) 28.sp else 32.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeatherTempChip(
            label = "最高",
            value = "${snapshot.temperatureMaxC.roundToInt()}℃",
            accent = Color(0xFFE53935),
        )
        WeatherTempChip(
            label = "最低",
            value = "${snapshot.temperatureMinC.roundToInt()}℃",
            accent = Color(0xFF1A73E8),
        )
    }
}

@Composable
private fun IndoorOutdoorSection(
    notice: IndoorOutdoorNotice,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "現在の温湿度",
            color = Color(0xFF444444),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        when (notice) {
            IndoorOutdoorNotice.Unavailable -> {
                Text(
                    text = IndoorOutdoorFailureLabel,
                    color = Color(0xFF333333),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            is IndoorOutdoorNotice.Available -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IndoorOutdoorMeter(
                        label = "室内",
                        climate = notice.climate,
                        outdoor = false,
                        modifier = Modifier.weight(1f),
                    )
                    IndoorOutdoorMeter(
                        label = "室外",
                        climate = notice.climate,
                        outdoor = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun IndoorOutdoorMeter(
    label: String,
    climate: IndoorOutdoorClimate,
    outdoor: Boolean,
    modifier: Modifier = Modifier,
) {
    val temperature = if (outdoor) {
        climate.outdoorTemperatureC
    } else {
        climate.indoorTemperatureC
    }
    val humidity = if (outdoor) {
        climate.outdoorHumidityPercent
    } else {
        climate.indoorHumidityPercent
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF3F6FA))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            color = if (outdoor) Color(0xFF1A73E8) else Color(0xFF2E7D32),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${temperature.roundToInt()}℃",
            color = Color(0xFF222222),
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "$humidity%",
            color = Color(0xFF444444),
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun WeatherTempChip(
    label: String,
    value: String,
    accent: Color,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF3F6FA))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = Color(0xFF222222),
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CalendarNotificationContent(
    notification: AppNotification,
    modifier: Modifier = Modifier,
) {
    val count = notification.eventCount ?: notification.calendarItems.size
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF1A73E8))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${count}件",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (count == 0) {
                Text(
                    text = "予定はありません",
                    color = Color(0xFF666666),
                    fontSize = 22.sp,
                )
            }
        }
        if (notification.calendarItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                notification.calendarItems.forEach { item ->
                    CalendarEventRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun CalendarEventRow(
    item: CalendarNotificationItem,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFF3F6FA))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (item.allDay) Color(0xFFE8F0FE) else Color(0xFFEEF7EE),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.timeLabel,
                color = if (item.allDay) Color(0xFF1A73E8) else Color(0xFF1B9E4B),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = item.title,
            color = Color(0xFF222222),
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DeviceAppIcon(
    icon: Bitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val image = remember(icon) { icon.asImageBitmap() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(128.dp)
                .clip(RoundedCornerShape(28.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun LineStatusHero(
    iconResIds: List<Int>,
    statusMark: NotificationStatusMark?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(top = 8.dp, end = 28.dp, bottom = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconResIds.forEach { resId ->
                Image(
                    painter = painterResource(resId),
                    contentDescription = null,
                    modifier = Modifier
                        .size(128.dp)
                        .clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        if (statusMark != null) {
            OutlinedStatusMark(
                mark = statusMark,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 26.dp, y = 26.dp),
                fontSizeSp = 60f,
            )
        }
    }
}

@Composable
fun OutlinedStatusMark(
    mark: NotificationStatusMark,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 48f,
    strokeWidth: Float = fontSizeSp * 0.25f,
    bodyStrokeWidth: Float = 0f,
) {
    val (glyph, color) = when (mark) {
        NotificationStatusMark.Normal -> "○" to Color(0xFF1B9E4B)
        NotificationStatusMark.Caution -> "△" to Color(0xFFE6A100)
        NotificationStatusMark.Stopped -> "×" to Color(0xFFD32F2F)
    }
    // △ は同じポイントでも視覚的に大きく見えるので、他記号と揃える
    val sizeScale = when (mark) {
        NotificationStatusMark.Caution -> 0.78f
        else -> 1f
    }
    val resolvedFontSize = fontSizeSp * sizeScale
    val resolvedStroke = strokeWidth * sizeScale
    val resolvedBodyStroke = bodyStrokeWidth * sizeScale
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = glyph,
            style = TextStyle(
                color = Color.White,
                fontSize = resolvedFontSize.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                drawStyle = Stroke(
                    width = resolvedStroke,
                    join = StrokeJoin.Round,
                    miter = 10f,
                ),
            ),
            maxLines = 1,
        )
        if (resolvedBodyStroke > 0f) {
            Text(
                text = glyph,
                style = TextStyle(
                    color = color,
                    fontSize = resolvedFontSize.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    drawStyle = Stroke(
                        width = resolvedBodyStroke,
                        join = StrokeJoin.Round,
                        miter = 10f,
                    ),
                ),
                maxLines = 1,
            )
        }
        Text(
            text = glyph,
            color = color,
            fontSize = resolvedFontSize.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

@Composable
fun StatusMarkBadge(
    mark: NotificationStatusMark,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    fontSizeSp: Float = 26f,
) {
    val (glyph, color) = when (mark) {
        NotificationStatusMark.Normal -> "○" to Color(0xFF1B9E4B)
        NotificationStatusMark.Caution -> "△" to Color(0xFFE6A100)
        NotificationStatusMark.Stopped -> "×" to Color(0xFFD32F2F)
    }
    // 背景円は clip しない（△ など字形が円からはみ出して切れないようにする）
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White, CircleShape)
                .border(3.dp, color, CircleShape),
        )
        Text(
            text = glyph,
            color = color,
            fontSize = fontSizeSp.sp,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun NotificationHistoryPanel(
    items: List<AppNotification>,
    onItemClick: (AppNotification) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    }
    val retainedTimeFormatter = remember {
        DateTimeFormatter.ofPattern("M/d H:mm", Locale.JAPANESE)
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "通知履歴",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            // 空のときも同じ領域を確保し、見出し位置が動かないようにする
            Text(
                text = "すべて消す",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 20.sp,
                modifier = Modifier
                    .alpha(if (items.isNotEmpty()) 1f else 0f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (items.isNotEmpty()) 0.12f else 0f))
                    .clickable(enabled = items.isNotEmpty(), onClick = onClearAll)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (items.isEmpty()) {
                Text(
                    text = "通知はありません",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 20.sp,
                )
            } else {
                items.forEach { item ->
                    val alpha = if (item.read) 0.55f else 1f
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onItemClick(item) }
                            .padding(20.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.categoryLabel,
                                color = Color.White.copy(alpha = 0.55f * alpha),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                            )
                            Text(
                                text = item.createdAt.format(
                                    if (item.retained) retainedTimeFormatter else timeFormatter,
                                ),
                                color = Color.White.copy(alpha = 0.5f * alpha),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            item.appIcon?.let { icon ->
                                Image(
                                    bitmap = icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                            }
                            item.iconResIds.take(2).forEach { resId ->
                                Image(
                                    painter = painterResource(resId),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            item.statusMark?.let { mark ->
                                OutlinedStatusMark(
                                    mark = mark,
                                    fontSizeSp = 28f,
                                    strokeWidth = 3.5f,
                                    bodyStrokeWidth = 5f,
                                )
                            }
                            if (item.isCalendar) {
                                val count = item.eventCount ?: item.calendarItems.size
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color(0xFF1A73E8).copy(alpha = 0.85f * alpha))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = "${count}件",
                                        color = Color.White.copy(alpha = alpha),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Text(
                                text = item.title,
                                color = Color.White.copy(alpha = alpha),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.isCalendar && item.calendarItems.isNotEmpty()) {
                            Text(
                                text = item.calendarItems
                                    .take(2)
                                    .joinToString(" / ") { "${it.timeLabel} ${it.title}" },
                                color = Color.White.copy(alpha = 0.7f * alpha),
                                fontSize = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                text = item.body,
                                color = Color.White.copy(alpha = 0.7f * alpha),
                                fontSize = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
