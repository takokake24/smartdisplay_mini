package app.smartdisplay.bus

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.ui.CircleCloseButton
import app.smartdisplay.ui.Panel
import app.smartdisplay.ui.RefreshChip
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import app.smartdisplay.util.showOsAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BusTimetableContent(
    now: LocalDateTime,
    repository: BusTimetableRepository,
    reminderScheduler: BusReminderScheduler,
    scheduledTrips: List<BusTrip>,
    onOpenReminders: () -> Unit,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = busServiceDate(now)
    var selectedDate by remember { mutableStateOf(today) }
    var selectedDirection by remember { mutableStateOf(BusDirection.KokubunjiMusashiKoganei) }
    var viaFilter by remember { mutableStateOf<BusViaFilter?>(null) }
    var showViaPanel by remember { mutableStateOf(false) }
    var viaPanelInteracted by remember { mutableStateOf(false) }
    // 営業日中の再表示はキャッシュを同期で使い、毎回取得中にしない
    var day by remember {
        mutableStateOf(repository.cachedDay(today, BusDirection.KokubunjiMusashiKoganei, today))
    }
    var loading by remember { mutableStateOf(day == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scrollToUpcoming by remember { mutableStateOf(true) }
    var scrollToTop by remember { mutableStateOf(false) }
    var loadRequestId by remember { mutableIntStateOf(0) }
    var stopTrip by remember { mutableStateOf<BusTrip?>(null) }
    val listState = rememberLazyListState()
    val viaListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scheduledTripIds = remember(scheduledTrips) { scheduledTrips.map { it.id }.toSet() }

    fun markViaPanelInteracted() {
        if (showViaPanel) {
            viaPanelInteracted = true
        }
    }

    fun scrollViaChipIntoView(via: BusViaFilter?) {
        scope.launch {
            if (via == null) {
                viaListState.scrollToItem(0)
                return@launch
            }
            val index = BusViaFilter.forDirection(selectedDirection).indexOf(via)
            if (index >= 0) {
                viaListState.scrollToItem(index)
            }
        }
    }

    fun requestTripListScroll() {
        val jumpToUpcoming = selectedDate == today
        scrollToUpcoming = jumpToUpcoming
        scrollToTop = !jumpToUpcoming
    }

    fun applyViaFilter(via: BusViaFilter?, scrollChipIntoView: Boolean = false) {
        viaFilter = via
        // 今日は次便、他日は先頭へ
        requestTripListScroll()
        if (scrollChipIntoView) {
            // メニューから選んだときだけ、チップが見える位置へ合わせる
            scrollViaChipIntoView(via)
        }
        showViaPanel = false
    }

    fun closeViaPanel() {
        if (viaPanelInteracted) {
            viaFilter = null
            requestTripListScroll()
            scrollViaChipIntoView(null)
        }
        showViaPanel = false
        viaPanelInteracted = false
    }
    val dateFormatter = remember(selectedDate, today) {
        val pattern = if (selectedDate.year == today.year) {
            "M月d日（E）"
        } else {
            "yyyy年M月d日（E）"
        }
        DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE)
    }
    val viaOptions = remember(selectedDirection) {
        BusViaFilter.forDirection(selectedDirection)
    }
    val legendCompanies = remember(selectedDirection) {
        when (selectedDirection) {
            BusDirection.KokubunjiMusashiKoganei,
            BusDirection.HanakoganeiShowa,
            -> listOf(BusCompany.Keio, BusCompany.Tachikawa)
            BusDirection.HitotsubashiKodaira -> listOf(BusCompany.Ginga, BusCompany.Seibu)
            BusDirection.KokubunjiDepart -> listOf(
                BusCompany.Keio,
                BusCompany.Tachikawa,
                BusCompany.Seibu,
                BusCompany.Community,
                BusCompany.TokyoAirportTransport,
            )
            BusDirection.MusashiKoganeiDepart -> listOf(
                BusCompany.Seibu,
                BusCompany.Kanto,
                BusCompany.Keio,
                BusCompany.KoganeiCommunity,
                BusCompany.TokyoAirportTransport,
            )
            BusDirection.ChuoDoFuchuDepart -> listOf(
                BusCompany.Keio,
                BusCompany.FujiQ,
                BusCompany.Kintetsu,
                BusCompany.JrBusKanto,
                BusCompany.Highway,
            )
        }
    }

    LaunchedEffect(now) {
        reminderScheduler.pruneExpired(now)
    }

    fun showAlert(message: String) {
        showOsAlert(context, message)
    }

    fun notifyFetchFailures(failedDay: BusTimetableDay?, thrown: Throwable?) {
        if (!InternetConnectivity.hasInternet() || thrown is NoInternetException) {
            return
        }
        val labels = buildList {
            failedDay?.failures?.forEach { (company, failed) ->
                if (failed) {
                    add(company.displayName)
                }
            }
            if (thrown != null && isEmpty()) {
                add("時刻表")
            }
        }
        if (labels.isEmpty()) {
            return
        }
        FetchFailureNotifier.clear(labels)
        if (FetchFailureNotifier.report(labels)) {
            onErrorSound()
        }
    }

    fun reload(
        date: LocalDate,
        direction: BusDirection,
        jumpToUpcoming: Boolean,
        forceRefresh: Boolean = false,
    ) {
        selectedDate = date
        selectedDirection = direction
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
        // 別タブの残像を出さない
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
            // 通信したときだけ失敗を通知（キャッシュヒットでは来ない）
            notifyFetchFailures(result.getOrNull(), error)
        }
    }

    LaunchedEffect(today) {
        reload(today, selectedDirection, jumpToUpcoming = true)
    }

    LaunchedEffect(selectedDirection) {
        viaListState.scrollToItem(0)
    }

    val trips = remember(day, viaFilter, selectedDate, selectedDirection) {
        val current = day
        if (current == null ||
            current.date != selectedDate ||
            current.direction != selectedDirection
        ) {
            emptyList()
        } else {
            val filter = viaFilter
            if (filter == null) {
                current.trips
            } else {
                current.trips.filter { it.matchesVia(filter) }
            }
        }
    }

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
                text = "バス時刻表",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val reminderLabel = if (scheduledTrips.isEmpty()) {
                    "乗車通知"
                } else {
                    "乗車通知 （${scheduledTrips.size}）"
                }
                Text(
                    text = reminderLabel,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 20.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (scheduledTrips.isNotEmpty()) {
                                Color.White.copy(alpha = 0.22f)
                            } else {
                                Color.White.copy(alpha = 0.12f)
                            },
                        )
                        .clickable {
                            onTapSound()
                            onOpenReminders()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BusDirection.entries.forEach { direction ->
                DirectionTab(
                    label = direction.tabLabel,
                    selected = selectedDirection == direction,
                    onClick = {
                        onTapSound()
                        markViaPanelInteracted()
                        viaFilter = null
                        reload(
                            selectedDate,
                            direction,
                            jumpToUpcoming = selectedDate == today,
                        )
                    },
                )
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
                    markViaPanelInteracted()
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
                                markViaPanelInteracted()
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
                    markViaPanelInteracted()
                    reload(selectedDate.plusDays(1), selectedDirection, jumpToUpcoming = false)
                },
            )
            if (selectedDate != today) {
                DateChip(
                    label = "今日",
                    onClick = {
                        onTapSound()
                        markViaPanelInteracted()
                        reload(today, selectedDirection, jumpToUpcoming = true)
                    },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "タップで15・10・5・3分前通知",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 16.sp,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // パネル中も破棄しない（選択後の横スクロールを確実にするため）。
                // メニュー展開中は透明にして、角からはみ出す白枠を見せない。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (showViaPanel) 0f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ViaListButton(
                        selected = false,
                        onClick = {
                            onTapSound()
                            if (showViaPanel) {
                                closeViaPanel()
                            } else {
                                viaPanelInteracted = false
                                showViaPanel = true
                            }
                        },
                    )
                    FilterChip(
                        label = "すべて",
                        selected = viaFilter == null,
                        onClick = {
                            onTapSound()
                            applyViaFilter(null)
                        },
                    )
                    LazyRow(
                        state = viaListState,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(viaOptions, key = { it.name }) { via ->
                            FilterChip(
                                label = via.filterLabel,
                                selected = viaFilter == via,
                                onClick = {
                                    onTapSound()
                                    applyViaFilter(via)
                                },
                            )
                        }
                    }
                }

                if (!showViaPanel) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        legendCompanies.forEach { company ->
                            LegendDot(
                                color = company.accent,
                                label = company.displayName,
                                failed = day?.failures?.get(company) == true,
                            )
                        }
                    }

                    when {
                        loading -> {
                            Text(
                                text = "時刻表を取得中…",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        trips.isEmpty() && (day?.anyFailed == true || errorMessage != null) -> {
                            Text(
                                text = "時刻表を取得できませんでした",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 22.sp,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        trips.isEmpty() -> {
                            Text(
                                text = "該当する便がありません",
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
                                    // 半透明は「今日」の過ぎた便だけ
                                    val past = selectedDate == today && !trip.departureAt.isAfter(now)
                                    BusTripRow(
                                        trip = trip,
                                        viaFilter = viaFilter,
                                        scheduled = trip.id in scheduledTripIds,
                                        dimmed = past,
                                        onClick = {
                                            onTapSound()
                                            stopTrip = trip
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showViaPanel) {
                ViaFilterPanel(
                    vias = viaOptions,
                    selected = viaFilter,
                    onSelect = { via ->
                        onTapSound()
                        applyViaFilter(via, scrollChipIntoView = true)
                    },
                    onClose = {
                        onTapSound()
                        closeViaPanel()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        stopTrip?.let { trip ->
            BusStopDialog(
                trip = trip,
                scheduled = trip.id in scheduledTripIds,
                repository = repository,
                onToggleReminder = {
                    when (reminderScheduler.toggle(trip)) {
                        BusReminderToggleResult.Ok -> onTapSound()
                        BusReminderToggleResult.PastDate -> {
                            onErrorSound()
                            showAlert("過去の日時は選択できません")
                        }
                        BusReminderToggleResult.FutureDate -> {
                            onErrorSound()
                            showAlert("明日以降は選択できません")
                        }
                        BusReminderToggleResult.Departed -> {
                            onErrorSound()
                            showAlert("すでに出発時刻を過ぎています")
                        }
                    }
                },
                onClose = { stopTrip = null },
                onTapSound = onTapSound,
                onErrorSound = onErrorSound,
            )
        }
    }
}

@Composable
fun BusRemindersContent(
    now: LocalDateTime,
    reminderScheduler: BusReminderScheduler,
    scheduledTrips: List<BusTrip>,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(now) {
        reminderScheduler.pruneExpired(now)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "乗車通知",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "発車の15・10・5・3分前に案内します。解除すると通知しません。",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 16.sp,
        )
        if (scheduledTrips.isEmpty()) {
            Text(
                text = "設定中の乗車通知はありません",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 20.sp,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                itemsIndexed(scheduledTrips, key = { _, trip -> trip.id }) { _, trip ->
                    Panel(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(trip.company.accent),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${formatBusDateTime(trip.departureAt)}発  ${trip.destinationDisplayText}",
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${trip.company.displayName} ${trip.routeName}  ${trip.originStop}",
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 16.sp,
                                )
                            }
                            Text(
                                text = "解除",
                                color = Color.White,
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.16f))
                                    .clickable {
                                        onTapSound()
                                        reminderScheduler.cancel(trip.id)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusTripRow(
    trip: BusTrip,
    viaFilter: BusViaFilter?,
    scheduled: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val viaArrival = viaFilter?.let { trip.viaArrivals[it] }
    val showArrival = when {
        viaFilter != null -> viaArrival != null
        else -> trip.arrivalAt != null && trip.arrivalStopLabel != null
    }
    // 経由地絞り込み時は経由停留所の着時刻のみ差し替え、行先名は常に本来の終点
    val arrivalAt = viaArrival?.at ?: trip.arrivalAt
    val arrivalStop = viaArrival?.stopLabel ?: trip.arrivalStopLabel

    Panel(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(trip.company.accent),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = formatBusTime(trip.departureAt),
                        color = Color.White.copy(alpha = if (dimmed) 0.45f else 1f),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "発",
                        color = Color.White.copy(alpha = if (dimmed) 0.35f else 0.65f),
                        fontSize = 18.sp,
                    )
                    if (showArrival && arrivalAt != null && arrivalStop != null) {
                        Text(
                            text = "→",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 20.sp,
                        )
                        Text(
                            text = formatBusTime(arrivalAt),
                            color = Color.White.copy(alpha = if (dimmed) 0.45f else 0.95f),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "着 $arrivalStop",
                            color = Color.White.copy(alpha = if (dimmed) 0.35f else 0.7f),
                            fontSize = 18.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${trip.company.displayName}  ${trip.routeName}  ${trip.destinationDisplayText}",
                    color = Color.White.copy(alpha = if (dimmed) 0.35f else 0.85f),
                    fontSize = 18.sp,
                )
                Text(
                    text = "${trip.originStop}発" +
                        (trip.arrivalNote?.let { "  $it" } ?: "") +
                        if (trip.isNightBus) "  ※深夜バス 運賃2倍" else "",
                    color = Color.White.copy(alpha = if (dimmed) 0.28f else 0.5f),
                    fontSize = 15.sp,
                )
            }
            if (scheduled) {
                Text(
                    text = "通知ON",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(trip.company.accent.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BusSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    fontSize: TextUnit,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    fontWeightSelected: FontWeight = FontWeight.Medium,
    fontWeightUnselected: FontWeight = FontWeight.Normal,
    unselectedAlpha: Float = 0.85f,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (selected) Color.Black else Color.White.copy(alpha = unselectedAlpha),
        fontSize = fontSize,
        fontWeight = if (selected) fontWeightSelected else fontWeightUnselected,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) Color.White else Color.White.copy(alpha = 0.12f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    )
}

@Composable
private fun DirectionTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BusSelectableChip(
        label = label,
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        fontSize = 18.sp,
        horizontalPadding = 16.dp,
        verticalPadding = 10.dp,
    )
}

@Composable
private fun ViaListButton(
    selected: Boolean,
    onClick: () -> Unit,
) {
    val lineColor = if (selected) Color.Black else Color.White.copy(alpha = 0.9f)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (selected) Color.White else Color.White.copy(alpha = 0.12f),
            )
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
                        .background(lineColor),
                )
            }
        }
    }
}

@Composable
private fun ViaFilterPanel(
    vias: List<BusViaFilter>,
    selected: BusViaFilter?,
    onSelect: (BusViaFilter?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .clip(shape)
            // 下に残しているチップ行が透けないよう、画面と同じ黒で覆う
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "行き先",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                CircleCloseButton(onClick = onClose)
            }
            val gridItems = remember(vias) {
                listOf<BusViaFilter?>(null) + vias
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(
                    items = gridItems,
                    key = { via -> via?.name ?: "all" },
                ) { via ->
                    ViaPanelChip(
                        label = via?.filterLabel ?: "すべて",
                        selected = selected == via,
                        onClick = { onSelect(via) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViaPanelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BusSelectableChip(
        label = label,
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        fontSize = 20.sp,
        horizontalPadding = 10.dp,
        verticalPadding = 16.dp,
        unselectedAlpha = 0.9f,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BusSelectableChip(
        label = label,
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        fontSize = 18.sp,
        horizontalPadding = 14.dp,
        verticalPadding = 8.dp,
        fontWeightSelected = FontWeight.Normal,
        fontWeightUnselected = FontWeight.Normal,
        modifier = modifier,
    )
}

@Composable
private fun DateChip(
    label: String,
    onClick: () -> Unit,
) {
    BusSelectableChip(
        label = label,
        selected = false,
        onClick = onClick,
        shape = CircleShape,
        fontSize = 18.sp,
        horizontalPadding = 14.dp,
        verticalPadding = 8.dp,
        fontWeightSelected = FontWeight.Normal,
        fontWeightUnselected = FontWeight.Normal,
        unselectedAlpha = 0.9f,
    )
}

@Composable
private fun LegendDot(
    color: Color,
    label: String,
    failed: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(12.dp)
                .height(12.dp)
                .clip(CircleShape)
                .background(color)
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
        )
        Text(
            text = label,
            color = if (failed) {
                Color(0xFFFF5252)
            } else {
                Color.White.copy(alpha = 0.6f)
            },
            fontSize = 15.sp,
        )
    }
}
