package app.smartdisplay.train

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
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
import app.smartdisplay.bus.formatBusTime
import app.smartdisplay.ui.CircleCloseButton
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

private val RouteDialogColor = Color(0xFF141414)
private val RouteCardShape = RoundedCornerShape(28.dp)
private const val RouteFetchLabel = "停車駅"

@Composable
fun TrainRouteDialog(
    trip: TrainTrip,
    date: LocalDate,
    direction: TrainDirection,
    repository: TrainTimetableRepository,
    onClose: () -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
) {
    var attempt by remember(trip.id) { mutableIntStateOf(0) }
    var route by remember(trip.id) { mutableStateOf<TrainRoute?>(null) }
    var failed by remember(trip.id) { mutableStateOf(false) }
    var offline by remember(trip.id) { mutableStateOf(false) }

    LaunchedEffect(trip.id, attempt) {
        failed = false
        offline = false
        route = null
        val result = runCatching {
            repository.fetchStops(date, direction, trip)
        }
        val error = result.exceptionOrNull()
        if (error is CancellationException) {
            return@LaunchedEffect
        }
        route = result.getOrNull()
        if (error != null) {
            offline = error is NoInternetException || !InternetConnectivity.hasInternet()
            failed = true
            if (!offline) {
                FetchFailureNotifier.clear(listOf(RouteFetchLabel))
                if (FetchFailureNotifier.report(listOf(RouteFetchLabel))) {
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
                        .clip(RouteCardShape)
                        .background(RouteDialogColor)
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RouteCardShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    val stops = route?.stops
                    RouteHeader(
                        trip = trip,
                        direction = direction,
                        innerRapid = stops?.let(::isTozaiInnerRapid) == true,
                        detailNote = route?.detailNote.orEmpty(),
                        onClose = {
                            onTapSound()
                            onClose()
                        },
                    )
                    when {
                        stops == null && !failed -> {
                            Text(
                                text = "停車駅を取得中…",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                            )
                        }
                        failed || stops == null -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text(
                                    text = if (offline) {
                                        "インターネットに接続されていません"
                                    } else {
                                        "停車駅を取得できませんでした"
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
                            RouteStopList(
                                stops = stops.orEmpty(),
                                accent = trip.accent,
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
private fun RouteHeader(
    trip: TrainTrip,
    direction: TrainDirection,
    innerRapid: Boolean,
    detailNote: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(direction.iconResId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = trip.typeLabel,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(trip.accent)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    text = trip.destination,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .alignByBaseline(),
                )
                if (trainShowsBoundSuffix(trip.destination)) {
                    Text(
                        text = "行き",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 14.sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                if (trip.originatesHere) {
                    Text(
                        text = "当駅始発",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .alignByBaseline()
                            .clip(RoundedCornerShape(6.dp))
                            .background(TrainOriginAccent)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (trip.trainName.isNotEmpty()) {
                Text(
                    text = trip.trainName,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val throughNote = buildString {
                if (trip.note.isNotEmpty()) {
                    append(trip.note)
                }
                if (detailNote.isNotEmpty()) {
                    if (isNotEmpty()) {
                        append(' ')
                    }
                    append(detailNote)
                }
                if (innerRapid) {
                    if (isNotEmpty()) {
                        append(' ')
                    }
                    append("東西線内快速")
                }
            }
            if (throughNote.isNotEmpty()) {
                Text(
                    text = throughNote,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "${direction.station.label} ${formatBusTime(trip.departureAt)}発",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        CircleCloseButton(onClick = onClose)
    }
}

@Composable
private fun RouteStopList(
    stops: List<TrainStop>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val originIndex = stops.indexOfFirst { it.isOrigin }
    var revealed by remember(stops) { mutableStateOf(originIndex <= 0) }
    // 着と発で行の高さが変わる。LazyColumn だと末尾でスクロール位置が往復する。
    LaunchedEffect(stops) {
        if (originIndex > 0) {
            val offset = with(density) {
                stops.take(originIndex).sumOf { routeStopRowHeight(it).roundToPx() }
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
            RouteStopRow(
                stop = stop,
                index = index,
                lastIndex = stops.lastIndex,
                passed = originIndex >= 0 && index < originIndex,
                accent = accent,
            )
        }
    }
}

private fun routeStopRowHeight(stop: TrainStop): Dp {
    val timeLabel = trainStopTimeLabel(stop.arrival, stop.departure)
    return when {
        stop.isOrigin -> 88.dp
        '\n' in timeLabel -> 72.dp
        else -> 56.dp
    }
}

@Composable
private fun RouteStopRow(
    stop: TrainStop,
    index: Int,
    lastIndex: Int,
    passed: Boolean,
    accent: Color,
) {
    val timeLabel = trainStopTimeLabel(stop.arrival, stop.departure)
    val rowHeight = routeStopRowHeight(stop)
    val textAlpha = if (passed) 0.42f else 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .then(
                if (stop.isOrigin) {
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
                fontSize = if (stop.isOrigin) 18.sp else 16.sp,
                fontWeight = if (stop.isOrigin) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.End,
                lineHeight = 22.sp,
            )
            RouteMarker(
                accent = accent,
                emphasized = stop.isOrigin,
                passed = passed,
                hasAbove = index > 0,
                hasBelow = index < lastIndex,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxHeight()
                    .width(36.dp),
            )
            Text(
                text = stop.name,
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = textAlpha),
                fontSize = if (stop.isOrigin) 26.sp else 20.sp,
                fontWeight = if (stop.isOrigin) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RouteMarker(
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
                        .background(RouteDialogColor, CircleShape),
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
