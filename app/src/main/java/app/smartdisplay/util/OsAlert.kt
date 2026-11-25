package app.smartdisplay.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong

private const val ToastVisibleMs = 2_800L

private data class AppToastMessage(
    val id: Long,
    val text: String,
)

private val toastSequence = AtomicLong(0L)
private val toastMessage = MutableStateFlow<AppToastMessage?>(null)
private val pendingToasts = ArrayDeque<String>()
private val toastGate = Any()

object AppToast {
    fun show(text: String) {
        if (text.isBlank()) {
            return
        }
        synchronized(toastGate) {
            if (toastMessage.value == null) {
                toastMessage.value = AppToastMessage(id = toastSequence.incrementAndGet(), text = text)
            } else {
                pendingToasts.addLast(text)
            }
        }
    }
}

private fun dismissToastIfCurrent(id: Long) {
    synchronized(toastGate) {
        val shown = toastMessage.value
        if (shown == null || shown.id != id) {
            return
        }
        val next = pendingToasts.removeFirstOrNull()
        toastMessage.value = if (next == null) {
            null
        } else {
            AppToastMessage(id = toastSequence.incrementAndGet(), text = next)
        }
    }
}

/** 画面下部中央に出て、一定時間で消えるトースト。続けて来た文は、今の表示のあとに出す。 */
fun showOsAlert(message: String) {
    AppToast.show(message)
}

@Composable
fun AppToastHost(modifier: Modifier = Modifier) {
    val current by toastMessage.collectAsState()
    LaunchedEffect(current?.id) {
        val shown = current ?: return@LaunchedEffect
        delay(ToastVisibleMs)
        dismissToastIfCurrent(shown.id)
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = current != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                text = current?.text.orEmpty(),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(start = 48.dp, end = 48.dp, bottom = 48.dp)
                    .fillMaxWidth(0.72f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE61E1E1E))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
            )
        }
    }
}
