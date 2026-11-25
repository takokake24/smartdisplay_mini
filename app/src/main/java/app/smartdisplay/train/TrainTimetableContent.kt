package app.smartdisplay.train

import android.app.DatePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.bus.busServiceDate
import app.smartdisplay.bus.formatBusTime
import app.smartdisplay.ui.CircleCloseButton
import app.smartdisplay.ui.Panel
import app.smartdisplay.ui.RefreshChip
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val FetchLabel = "鉄道時刻表"

@Composable
fun TrainTimetableContent(
    now: LocalDateTime,
    repository: TrainTimetableRepository,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = busServiceDate(now)
    var selectedDate by remember { mutableStateOf(today) }
    var selectedDirection by remember { mutableStateOf(TrainDirection.ChuoTokyo) }
    var typeFilter by remember { mutableStateOf<Set<String>?>(null) }
    var destinationFilter by remember { mutableStateOf<Set<String>?>(null) }
    var originFilter by remember { mutableStateOf<Set<String>?>(null) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var showLineMenu by remember { mutableStateOf(false) }
    var routeTrip by remember { mutableStateOf<TrainTrip?>(null) }
    var day by remember {
        mutableStateOf(repository.cachedDay(today, TrainDirection.ChuoTokyo, today))
    }
    var loading by remember { mutableStateOf(day == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scrollToUpcoming by remember { mutableStateOf(true) }
    var scrollToTop by remember { mutableStateOf(false) }
    var loadRequestId by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val directionListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dateFormatter = remember(selectedDate, today) {
        val pattern = if (selectedDate.year == today.year) {
            "M月d日（E）"
        } else {
            "yyyy年M月d日（E）"
        }
        DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE)
    }

    fun requestListScroll() {
        val jumpToUpcoming = selectedDate == today
        scrollToUpcoming = jumpToUpcoming
        scrollToTop = !jumpToUpcoming
    }

    fun reload(
        date: LocalDate,
        direction: TrainDirection,
        jumpToUpcoming: Boolean,
        forceRefresh: Boolean = false,
        resetFilters: Boolean = false,
    ) {
        if (date != selectedDate || direction != selectedDirection) {
            routeTrip = null
        }
        selectedDate = date
        selectedDirection = direction
        if (resetFilters) {
            typeFilter = null
            destinationFilter = null
            originFilter = null
            showFilterPanel = false
        }
        scrollToUpcoming = jumpToUpcoming
        scrollToTop = !jumpToUpcoming
        errorMessage = null
        if (!forceRefresh) {
            repository.cachedDay(date, direction, today)?.let { cached ->
                day = cached
                loading = false
                return
            }
        }
        if (day?.date != date || day?.direction != direction) {
            day = null
        }
        loading = true
        val requestId = loadRequestId + 1
        loadRequestId = requestId
        scope.launch {
            val result = runCatching {
                repository.fetchDay(
                    date = date,
                    direction = direction,
                    forceRefresh = forceRefresh,
                    today = today,
                )
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                return@launch
            }
            if (requestId != loadRequestId) {
                return@launch
            }
            day = result.getOrNull()
            errorMessage = error?.message
            loading = false
            if (error != null) {
                notifyFetchFailure(error, onErrorSound)
            }
        }
    }

    LaunchedEffect(today) {
        reload(today, selectedDirection, jumpToUpcoming = true)
    }

    val stationDirections = remember(selectedDirection.station) {
        TrainDirection.entries.filter { it.station == selectedDirection.station }
    }

    LaunchedEffect(selectedDirection.station) {
        directionListState.scrollToItem(0)
    }

    val allTrips = remember(day, selectedDate, selectedDirection) {
        val current = day
        if (current == null || current.date != selectedDate || current.direction != selectedDirection) {
            emptyList()
        } else {
            current.trips
        }
    }
    val types = remember(allTrips) { orderedTrainTypes(allTrips.map { it.typeLabel }) }
    val destinations = remember(allTrips) { orderedDestinations(allTrips.map { it.destination }) }
    val trips = remember(allTrips, typeFilter, destinationFilter, originFilter) {
        val selectedTypes = typeFilter
        val selectedDestinations = destinationFilter
        val selectedOrigins = originFilter
        allTrips.filter { trip ->
            (selectedTypes == null || trip.typeLabel in selectedTypes) &&
                (selectedDestinations == null || trip.destination in selectedDestinations) &&
                (selectedOrigins == null || trainOriginFilterKey(trip.originatesHere) in selectedOrigins)
        }
    }
    val filterSummary = trainFilterSummary(
        typeFilter,
        destinationFilter,
        originFilter,
        types,
        destinations,
    )

    LaunchedEffect(trips, selectedDate, loading, scrollToUpcoming, scrollToTop) {
        if (loading || trips.isEmpty()) {
            return@LaunchedEffect
        }
        when {
            scrollToUpcoming && selectedDate == today -> {
                val index = trips.indexOfFirst { it.departureAt.isAfter(now) }
                    .takeIf { it >= 0 }
                    ?: trips.lastIndex
                listState.scrollToItem(index.coerceAtLeast(0))
                scrollToUpcoming = false
                scrollToTop = false
            }
            scrollToTop -> {
                listState.scrollToItem(0)
                scrollToTop = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "鉄道時刻表",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            RefreshChip(
                onClick = {
                    onTapSound()
                    reload(
                        selectedDate,
                        selectedDirection,
                        jumpToUpcoming = selectedDate == today,
                        forceRefresh = true,
                    )
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrainStation.entries.forEach { station ->
                StationTab(
                    label = station.label,
                    selected = selectedDirection.station == station,
                    onClick = {
                        onTapSound()
                        if (station == selectedDirection.station) {
                            return@StationTab
                        }
                        showLineMenu = false
                        val direction = TrainDirection.entries.first { it.station == station }
                        reload(
                            selectedDate,
                            direction,
                            jumpToUpcoming = selectedDate == today,
                            resetFilters = true,
                        )
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilterMenuButton(
                onClick = {
                    onTapSound()
                    showFilterPanel = false
                    showLineMenu = !showLineMenu
                },
            )
            LazyRow(
                state = directionListState,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(stationDirections, key = { it.name }) { direction ->
                    DirectionTab(
                        direction = direction,
                        selected = selectedDirection == direction,
                        onClick = {
                            onTapSound()
                            showLineMenu = false
                            reload(
                                selectedDate,
                                direction,
                                jumpToUpcoming = selectedDate == today,
                                resetFilters = true,
                            )
                        },
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DateChip(
                label = "◀",
                onClick = {
                    onTapSound()
                    reload(selectedDate.minusDays(1), selectedDirection, jumpToUpcoming = false)
                },
            )
            Text(
                text = selectedDate.format(dateFormatter),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        onTapSound()
                        DatePickerDialog(
                            context,
                            { _, year, month, dayOfMonth ->
                                val picked = LocalDate.of(year, month + 1, dayOfMonth)
                                reload(
                                    picked,
                                    selectedDirection,
                                    jumpToUpcoming = picked == today,
                                )
                            },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth,
                        ).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            DateChip(
                label = "▶",
                onClick = {
                    onTapSound()
                    reload(selectedDate.plusDays(1), selectedDirection, jumpToUpcoming = false)
                },
            )
            if (selectedDate != today) {
                DateChip(
                    label = "今日",
                    onClick = {
                        onTapSound()
                        reload(today, selectedDirection, jumpToUpcoming = true)
                    },
                )
            }
        }

        DirectionHeading(heading = selectedDirection.heading)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (showFilterPanel) 0f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FilterMenuButton(
                        onClick = {
                            onTapSound()
                            showLineMenu = false
                            showFilterPanel = !showFilterPanel
                        },
                    )
                    Text(
                        text = filterSummary,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onTapSound()
                                showLineMenu = false
                                showFilterPanel = true
                            }
                            .padding(vertical = 6.dp),
                    )
                }

                if (!showFilterPanel && !showLineMenu) {
                    when {
                        loading -> {
                            Text(
                                text = "時刻表を取得中…",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        trips.isEmpty() && errorMessage != null -> {
                            Text(
                                text = "時刻表を取得できませんでした",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        trips.isEmpty() -> {
                            Text(
                                text = "該当する列車がありません",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        else -> {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(bottom = 20.dp),
                            ) {
                                itemsIndexed(trips, key = { _, trip -> trip.id }) { _, trip ->
                                    val past = selectedDate == today && !trip.departureAt.isAfter(now)
                                    TrainTripRow(
                                        trip = trip,
                                        dimmed = past,
                                        onClick = {
                                            onTapSound()
                                            routeTrip = trip
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showLineMenu) {
                TrainLineMenu(
                    directions = stationDirections,
                    selected = selectedDirection,
                    onSelect = { direction ->
                        onTapSound()
                        showLineMenu = false
                        val index = stationDirections.indexOf(direction)
                        if (index >= 0) {
                            scope.launch { directionListState.scrollToItem(index) }
                        }
                        if (direction != selectedDirection) {
                            reload(
                                selectedDate,
                                direction,
                                jumpToUpcoming = selectedDate == today,
                                resetFilters = true,
                            )
                        }
                    },
                    onClose = {
                        onTapSound()
                        showLineMenu = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (showFilterPanel) {
                TrainFilterPanel(
                    types = types,
                    destinations = destinations,
                    typeFilter = typeFilter,
                    destinationFilter = destinationFilter,
                    originFilter = originFilter,
                    onToggleType = { type ->
                        onTapSound()
                        typeFilter = toggleTrainFilter(typeFilter, type, types)
                        requestListScroll()
                    },
                    onToggleDestination = { destination ->
                        onTapSound()
                        destinationFilter = toggleTrainFilter(destinationFilter, destination, destinations)
                        requestListScroll()
                    },
                    onToggleOrigin = { origin ->
                        onTapSound()
                        originFilter = toggleTrainFilter(originFilter, origin, TrainOriginFilters)
                        requestListScroll()
                    },
                    onSelectAllTypes = {
                        onTapSound()
                        typeFilter = toggleAllTrainFilter(typeFilter)
                        requestListScroll()
                    },
                    onSelectAllDestinations = {
                        onTapSound()
                        destinationFilter = toggleAllTrainFilter(destinationFilter)
                        requestListScroll()
                    },
                    onSelectAllOrigins = {
                        onTapSound()
                        originFilter = toggleAllTrainFilter(originFilter)
                        requestListScroll()
                    },
                    onClose = {
                        onTapSound()
                        showFilterPanel = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        routeTrip?.let { trip ->
            TrainRouteDialog(
                trip = trip,
                date = selectedDate,
                direction = selectedDirection,
                repository = repository,
                onClose = { routeTrip = null },
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
            )
        }
    }
}

private fun notifyFetchFailure(error: Throwable, onErrorSound: () -> Unit) {
    if (!InternetConnectivity.hasInternet() || error is NoInternetException) {
        return
    }
    FetchFailureNotifier.clear(listOf(FetchLabel))
    if (FetchFailureNotifier.report(listOf(FetchLabel))) {
        onErrorSound()
    }
}

@Composable
private fun OriginLabel(modifier: Modifier = Modifier) {
    Text(
        text = "当駅始発",
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TrainOriginAccent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun TrainTripRow(
    trip: TrainTrip,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Panel(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (dimmed) 0.45f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(if (trip.trainName.isNotEmpty() || trip.note.isNotEmpty()) 72.dp else 56.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trip.accent),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = formatBusTime(trip.departureAt),
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "発",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
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
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 22.sp,
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
                        OriginLabel(modifier = Modifier.alignByBaseline())
                    }
                }
                if (trip.trainName.isNotEmpty()) {
                    Text(
                        text = trip.trainName,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (trip.note.isNotEmpty()) {
                    Text(
                        text = trip.note,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StationTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White.copy(alpha = 0.9f),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DirectionHeading(heading: String) {
    val parts = remember(heading) { trainHeadingParts(heading) }
    val text = buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            when (part) {
                is TrainHeadingPart.Text -> append(part.value)
                is TrainHeadingPart.Mark -> appendInlineContent("mark$index")
            }
        }
    }
    val inlineContent = parts.mapIndexedNotNull { index, part ->
        if (part is TrainHeadingPart.Mark) {
            "mark$index" to InlineTextContent(
                Placeholder(
                    width = 24.sp,
                    height = 24.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Image(
                    painter = painterResource(part.iconResId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            null
        }
    }.toMap()
    Text(
        text = text,
        inlineContent = inlineContent,
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun DirectionTab(
    direction: TrainDirection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(direction.iconResId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = direction.chipLabel(),
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.9f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun DateChip(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = Color.White.copy(alpha = 0.9f),
        fontSize = 18.sp,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun FilterMenuButton(
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(18.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color.White.copy(alpha = 0.9f)),
                )
            }
        }
    }
}

@Composable
private fun TrainLineMenu(
    directions: List<TrainDirection>,
    selected: TrainDirection,
    onSelect: (TrainDirection) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "路線",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                CircleCloseButton(onClick = onClose)
            }
            directions.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { direction ->
                        val chosen = direction == selected
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (chosen) Color.White else Color.White.copy(alpha = 0.12f))
                                .clickable { onSelect(direction) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Image(
                                painter = painterResource(direction.iconResId),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = direction.chipLabel(),
                                color = if (chosen) Color.Black else Color.White.copy(alpha = 0.9f),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    repeat(3 - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TrainFilterPanel(
    types: List<String>,
    destinations: List<String>,
    typeFilter: Set<String>?,
    destinationFilter: Set<String>?,
    originFilter: Set<String>?,
    onToggleType: (String) -> Unit,
    onToggleDestination: (String) -> Unit,
    onToggleOrigin: (String) -> Unit,
    onSelectAllTypes: () -> Unit,
    onSelectAllDestinations: () -> Unit,
    onSelectAllOrigins: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "種別",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                CircleCloseButton(onClick = onClose)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChoice(
                    label = "すべて",
                    selected = typeFilter == null,
                    onClick = onSelectAllTypes,
                )
                types.forEach { type ->
                    FilterChoice(
                        label = type,
                        selected = typeFilter == null || type in typeFilter,
                        accent = trainTypeColor(type),
                        onClick = { onToggleType(type) },
                    )
                }
            }
            Text(
                text = "行き先",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChoice(
                    label = "すべて",
                    selected = destinationFilter == null,
                    onClick = onSelectAllDestinations,
                )
                destinations.forEach { destination ->
                    FilterChoice(
                        label = destination,
                        selected = destinationFilter == null || destination in destinationFilter,
                        onClick = { onToggleDestination(destination) },
                    )
                }
            }
            Text(
                text = "当駅始発",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilterChoice(
                    label = "すべて",
                    selected = originFilter == null,
                    onClick = onSelectAllOrigins,
                )
                TrainOriginFilters.forEach { origin ->
                    FilterChoice(
                        label = origin,
                        selected = originFilter == null || origin in originFilter,
                        onClick = { onToggleOrigin(origin) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    accent: Color? = null,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (accent != null) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.9f),
            fontSize = 18.sp,
        )
    }
}
