package app.smartdisplay.memorial

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.smartdisplay.ui.CircleCloseButton
import app.smartdisplay.ui.Panel
import app.smartdisplay.ui.RefreshChip
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MemorialDayContent(
    now: LocalDateTime,
    repository: MemorialRepository,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = now.toLocalDate()
    val scope = rememberCoroutineScope()
    var selectedDate by remember { mutableStateOf(today) }
    var days by remember { mutableStateOf(repository.cached(today)) }
    var loading by remember { mutableStateOf(days == null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadRequestId by remember { mutableIntStateOf(0) }
    var selectedDay by remember { mutableStateOf<MemorialDay?>(null) }
    var detailText by remember { mutableStateOf<String?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailRequestId by remember { mutableIntStateOf(0) }
    val dateFormatter = remember(selectedDate, today) {
        val pattern = if (selectedDate.year == today.year) {
            "M月d日（E）"
        } else {
            "yyyy年M月d日（E）"
        }
        DateTimeFormatter.ofPattern(pattern, Locale.JAPANESE)
    }

    fun reload(date: LocalDate, forceRefresh: Boolean = false) {
        selectedDate = date
        errorMessage = null
        if (!forceRefresh) {
            repository.cached(date)?.let { cached ->
                days = cached
                loading = false
                return
            }
        }
        days = null
        loading = true
        val requestId = loadRequestId + 1
        loadRequestId = requestId
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.load(date, force = forceRefresh)
                }
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                throw error
            }
            if (requestId != loadRequestId) {
                return@launch
            }
            days = result.getOrNull()
            errorMessage = error?.let { memorialLoadError(it) }
            loading = false
            if (error != null) {
                onErrorSound()
            }
        }
    }

    fun openDetail(day: MemorialDay) {
        onTapSound()
        selectedDay = day
        detailError = null
        repository.cachedDetail(day.id)?.let { cached ->
            detailText = cached
            detailLoading = false
            return
        }
        detailText = null
        detailLoading = true
        val requestId = detailRequestId + 1
        detailRequestId = requestId
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    repository.loadDetail(day.id)
                }
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                throw error
            }
            if (requestId != detailRequestId) {
                return@launch
            }
            detailText = result.getOrNull()
            detailError = error?.let { memorialLoadError(it, detail = true) }
            detailLoading = false
            if (error != null) {
                onErrorSound()
            }
        }
    }

    fun closeDetail() {
        onTapSound()
        selectedDay = null
        detailRequestId += 1
    }

    LaunchedEffect(Unit) {
        reload(today)
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
                text = "記念日",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            RefreshChip(
                onClick = {
                    onTapSound()
                    reload(selectedDate, forceRefresh = true)
                },
                enabled = !loading,
            )
        }
        Text(
            text = "日本記念日協会の認定記念日",
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 16.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MemorialDateChip(
                label = "◀",
                onClick = {
                    onTapSound()
                    reload(selectedDate.minusDays(1))
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
                                reload(LocalDate.of(year, month + 1, dayOfMonth))
                            },
                            selectedDate.year,
                            selectedDate.monthValue - 1,
                            selectedDate.dayOfMonth,
                        ).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            MemorialDateChip(
                label = "▶",
                onClick = {
                    onTapSound()
                    reload(selectedDate.plusDays(1))
                },
            )
            if (selectedDate != today) {
                MemorialDateChip(
                    label = "今日",
                    onClick = {
                        onTapSound()
                        reload(today)
                    },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "タップで説明",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 16.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                loading -> {
                    StatusText("記念日を取得中…")
                }
                errorMessage != null -> {
                    StatusText(errorMessage ?: "記念日を取得できませんでした")
                }
                days.isNullOrEmpty() -> {
                    StatusText("認定されている記念日はありません")
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        items(days.orEmpty(), key = { it.id }) { day ->
                            Panel(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { openDetail(day) },
                            ) {
                                Text(
                                    text = day.listLabel,
                                    color = Color.White,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    selectedDay?.let { day ->
        MemorialDetailDialog(
            day = day,
            detail = detailText,
            loading = detailLoading,
            error = detailError,
            onClose = ::closeDetail,
        )
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 22.sp,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun MemorialDateChip(
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

private fun memorialLoadError(error: Throwable, detail: Boolean = false): String {
    return if (error is NoInternetException) {
        "インターネットに接続ができませんでした。"
    } else if (detail) {
        "説明を取得できませんでした"
    } else {
        "記念日を取得できませんでした"
    }
}

@Composable
private fun MemorialDetailDialog(
    day: MemorialDay,
    detail: String?,
    loading: Boolean,
    error: String?,
    onClose: () -> Unit,
) {
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
                    onClick = onClose,
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
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF141414))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 28.dp, vertical = 22.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = day.listLabel,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    CircleCloseButton(onClick = onClose)
                }
                if (day.scheduleNote.isNotEmpty()) {
                    Text(
                        text = day.scheduleNote,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 8.dp, end = 48.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 18.dp),
                ) {
                    when {
                        loading -> StatusText("説明を取得中…")
                        error != null -> StatusText(error)
                        else -> {
                            Text(
                                text = detail.orEmpty(),
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 20.sp,
                                lineHeight = 32.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
            }
        }
    }
}
