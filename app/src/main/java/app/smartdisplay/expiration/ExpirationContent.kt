package app.smartdisplay.expiration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.ui.Panel
import app.smartdisplay.ui.RefreshChip
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val ExpiredColor = Color(0xFFFF5C5C)
private val SoonColor = Color(0xFFFF9A3C)
private val HintColor = Color.White.copy(alpha = 0.45f)

@Composable
fun ExpirationContent(
    today: LocalDate,
    onTapSound: () -> Unit,
    onErrorSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ExpirationItem>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var loadRequestId by remember { mutableIntStateOf(0) }

    fun reload() {
        errorMessage = null
        loading = true
        val requestId = loadRequestId + 1
        loadRequestId = requestId
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { fetchAllExpirations() }
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                throw error
            }
            if (requestId != loadRequestId) {
                return@launch
            }
            items = result.getOrNull()?.let { sortedExpirations(it) }
            errorMessage = error?.let { expirationLoadError(it) }
            loading = false
            if (error != null) {
                onErrorSound()
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
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
                text = "賞味期限管理",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium,
            )
            RefreshChip(
                onClick = {
                    onTapSound()
                    reload()
                },
                enabled = !loading,
            )
        }
        when {
            loading && items == null -> {
                StatusText("賞味期限を取得中…")
            }
            errorMessage != null && items == null -> {
                StatusText(errorMessage ?: "賞味期限を取得できませんでした")
            }
            items.isNullOrEmpty() -> {
                StatusText("登録されている賞味期限はありません")
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(items.orEmpty(), key = { it.id }) { item ->
                        ExpirationRow(item = item, today = today)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpirationRow(
    item: ExpirationItem,
    today: LocalDate,
) {
    val days = item.remainingDaysOn(today)
    val date = item.expirationDate?.let {
        expirationDateLabel(it, today, includeYear = true)
    } ?: "日付不明"
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.quantity}個　$date",
                    color = HintColor,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            ExpirationRemainingLabel(days)
        }
    }
}

@Composable
private fun ExpirationRemainingLabel(days: Int) {
    val urgency = expirationUrgency(days)
    val background = when (urgency) {
        ExpirationUrgency.Expired -> ExpiredColor
        ExpirationUrgency.Soon -> SoonColor
        ExpirationUrgency.Normal -> Color.White.copy(alpha = 0.14f)
    }
    val content = if (urgency == ExpirationUrgency.Normal) {
        Color.White
    } else {
        Color(0xFF1A1A1A)
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 108.dp)
            .clip(shape)
            .background(background)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            days < 0 -> {
                RemainingNumber("${expirationDigits(days)}日", content)
                RemainingCaption("超過", content)
            }
            days == 0 -> {
                Text(
                    text = "今日",
                    color = content,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            else -> {
                RemainingCaption("あと", content)
                RemainingNumber("${expirationDigits(days)}日", content)
            }
        }
    }
}

@Composable
private fun RemainingNumber(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
private fun RemainingCaption(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
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

private fun expirationLoadError(error: Throwable): String {
    return when (error) {
        is NoInternetException -> "インターネットに接続ができませんでした。"
        is ExpirationAuthMissingException -> "賞味期限の接続情報がありません。"
        else -> "賞味期限を取得できませんでした"
    }
}
