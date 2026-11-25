package app.smartdisplay.bus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.smartdisplay.R
import app.smartdisplay.train.trainStopTimeLabel
import app.smartdisplay.ui.CircleCloseButton
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

private val StopDialogColor = Color(0xFF141414)
private val StopCardShape = RoundedCornerShape(28.dp)
private const val StopFetchLabel = "停車停留所"

@Composable
fun BusStopDialog(
    trip: BusTrip,
    scheduled: Boolean,
    repository: BusTimetableRepository,
    onToggleReminder: () -> Unit,
    onClose: () -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
) {
    var attempt by remember(trip.id) { mutableIntStateOf(0) }
    val embedded = (trip.stopRef as? BusStopRef.Embedded)?.stops
    var stops by remember(trip.id) { mutableStateOf(embedded) }
    var failed by remember(trip.id) { mutableStateOf(false) }
    var offline by remember(trip.id) { mutableStateOf(false) }

    LaunchedEffect(trip.id, attempt) {
        failed = false
        offline = false
        if (embedded != null) {
            stops = embedded
            return@LaunchedEffect
        }
        stops = null
        val result = runCatching { repository.fetchStops(trip) }
        val error = result.exceptionOrNull()
        if (error is CancellationException) {
            return@LaunchedEffect
        }
        stops = result.getOrNull()
        if (error != null) {
            offline = error is NoInternetException || !InternetConnectivity.hasInternet()
            failed = true
            if (!offline) {
                FetchFailureNotifier.clear(listOf(StopFetchLabel))
                if (FetchFailureNotifier.report(listOf(StopFetchLabel))) {
                    onErrorSound()
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onTapSound()
                        onClose()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val cardWidth = minOf(maxWidth * 0.86f, 760.dp)
                Column(
                    modifier = Modifier
                        .width(cardWidth)
                        .fillMaxHeight(0.9f)
                        .clip(StopCardShape)
                        .background(StopDialogColor)
                        .border(1.dp, Color.White.copy(alpha = 0.16f), StopCardShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    StopHeader(
                        trip = trip,
                        scheduled = scheduled,
                        onToggleReminder = onToggleReminder,
                        onClose = {
                            onTapSound()
                            onClose()
                        },
                    )
                    val loaded = stops
                    when {
                        loaded == null && !failed -> {
                            Text(
                                text = "停車停留所を取得中…",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                            )
                        }
                        failed || loaded == null -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    text = if (offline) {
                                        "インターネットに接続されていません"
                                    } else {
                                        "停車停留所を取得できませんでした"
                                    },
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 22.sp,
                                )
                                Text(
                                    text = "再取得",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.12f))
                                        .clickable {
                                            onTapSound()
                                            attempt += 1
                                        }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                        else -> {
                            StopList(
                                stops = loaded,
                                origin = trip.originStop,
                                departure = trip.departureAt.toLocalTime(),
                                accent = trip.company.accent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopHeader(
    trip: BusTrip,
    scheduled: Boolean,
    onToggleReminder: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${trip.company.displayName}  ${trip.routeName}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = trip.destinationDisplayText,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val note = buildString {
                append("${trip.originStop} ${formatBusTime(trip.departureAt)}発")
                trip.arrivalNote?.let {
                    append("  ")
                    append(it)
                }
                if (trip.isNightBus) {
                    append("  ※深夜バス 運賃2倍")
                }
            }
            Text(
                text = note,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReminderBell(
                scheduled = scheduled,
                accent = trip.company.accent,
                onClick = onToggleReminder,
            )
            CircleCloseButton(onClick = onClose)
        }
    }
}

@Composable
private fun ReminderBell(
    scheduled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (scheduled) accent else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (scheduled) R.drawable.ic_bell else R.drawable.ic_bell_outline,
            ),
            contentDescription = if (scheduled) "乗車通知を解除" else "乗車通知",
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun StopList(
    stops: List<BusStop>,
    origin: String,
    departure: java.time.LocalTime,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val originIndex = remember(stops, origin, departure) {
        busOriginIndex(stops, origin, departure)
    }
    var revealed by remember(stops) { mutableStateOf(originIndex <= 0) }
    LaunchedEffect(stops) {
        if (originIndex > 0) {
            val offset = with(density) {
                stops.take(originIndex).sumOf { stopRowHeight(it).roundToPx() }
            }
            if (scrollState.maxValue == 0) {
                withFrameNanos { }
            }
            scrollState.scrollTo(offset.coerceAtMost(scrollState.maxValue))
        }
        revealed = true
    }
    Column(
        modifier = modifier
            .alpha(if (revealed) 1f else 0f)
            .verticalScroll(scrollState)
            .padding(start = 16.dp, end = 20.dp, bottom = 24.dp, top = 4.dp),
    ) {
        stops.forEachIndexed { index, stop ->
            StopRow(
                stop = stop,
                index = index,
                lastIndex = stops.lastIndex,
                passed = originIndex >= 0 && index < originIndex,
                emphasized = index == originIndex,
                accent = accent,
            )
        }
    }
}

private fun busOriginIndex(
    stops: List<BusStop>,
    origin: String,
    departure: java.time.LocalTime,
): Int {
    fun core(value: String) = value.substringBefore("（").substringBefore("(").trim()
    val from = core(origin)
    if (from.isEmpty()) {
        return -1
    }
    val hits = stops.indices.filter { index ->
        val name = core(stops[index].name)
        name == from || name.contains(from)
    }
    if (hits.isEmpty()) {
        return -1
    }
    val target = departure.hour * 60 + departure.minute
    return hits.minBy { index ->
        val time = stops[index].departure ?: stops[index].arrival
        if (time == null) {
            Int.MAX_VALUE
        } else {
            abs(time.hour * 60 + time.minute - target)
        }
    }
}

private fun stopRowHeight(stop: BusStop): Dp {
    val timeLabel = trainStopTimeLabel(stop.arrival, stop.departure)
    return when {
        '\n' in timeLabel -> 72.dp
        else -> 56.dp
    }
}

@Composable
private fun StopRow(
    stop: BusStop,
    index: Int,
    lastIndex: Int,
    passed: Boolean,
    emphasized: Boolean,
    accent: Color,
) {
    val timeLabel = trainStopTimeLabel(stop.arrival, stop.departure)
    val rowHeight = if (emphasized) 88.dp else stopRowHeight(stop)
    val textAlpha = if (passed) 0.42f else 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(
                if (emphasized) {
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(accent.copy(alpha = 0.22f))
                        .border(1.5.dp, accent.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timeLabel,
                modifier = Modifier.width(112.dp),
                color = Color.White.copy(alpha = textAlpha),
                fontSize = if (emphasized) 18.sp else 16.sp,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.End,
                lineHeight = 22.sp,
            )
            StopMarker(
                accent = accent,
                emphasized = emphasized,
                passed = passed,
                hasAbove = index > 0,
                hasBelow = index < lastIndex,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxHeight()
                    .width(36.dp),
            )
            Text(
                text = stop.displayName,
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = textAlpha),
                fontSize = if (emphasized) 26.sp else 20.sp,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StopMarker(
    accent: Color,
    emphasized: Boolean,
    passed: Boolean,
    hasAbove: Boolean,
    hasBelow: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (hasAbove) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(3.dp)
                    .fillMaxHeight(0.5f)
                    .background(accent),
            )
        }
        if (hasBelow) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(3.dp)
                    .fillMaxHeight(0.5f)
                    .background(accent),
            )
        }
        when {
            emphasized -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.35f)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp)
                        .border(3.dp, Color.White, CircleShape)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            passed -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(16.dp)
                        .border(2.dp, accent.copy(alpha = 0.75f), CircleShape)
                        .padding(3.dp)
                        .background(StopDialogColor, CircleShape),
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}
