package app.smartdisplay.away

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Ink = Color(0xFF3E2723)
private val KeyColor = Color.White.copy(alpha = 0.78f)
private val AlertBackground = Brush.verticalGradient(
    colors = listOf(
        Color(0xFFFFF59D),
        Color(0xFFFFEB3B),
        Color(0xFFFBC02D),
    ),
)

@Composable
fun AwayAlertScreen(
    controller: AwayAlertController,
    onTapSound: () -> Unit,
    onBeep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var enteringPin by remember { mutableStateOf(false) }
    var idleToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(enteringPin, idleToken) {
        if (!enteringPin) {
            return@LaunchedEffect
        }
        delay(PinIdleMs)
        enteringPin = false
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AlertBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (!enteringPin) {
            AlertMessage(onTap = {
                onTapSound()
                enteringPin = true
            })
        } else {
            PinEntry(
                controller = controller,
                onInteract = { idleToken += 1 },
                onTapSound = onTapSound,
                onBeep = onBeep,
            )
        }
    }
}

@Composable
private fun AlertMessage(onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "アラート",
            color = Ink,
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "外出モードのアラートが発動しました",
            color = Ink,
            fontSize = 40.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp),
        )
    }
}

@Composable
private fun PinEntry(
    controller: AwayAlertController,
    onInteract: () -> Unit,
    onTapSound: () -> Unit,
    onBeep: () -> Unit,
) {
    val interact by rememberUpdatedState(onInteract)
    val until by controller.lockoutUntilElapsed.collectAsState()
    val unlocking by controller.unlocking.collectAsState()
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var entry by remember { mutableStateOf("") }
    LaunchedEffect(until) {
        while (SystemClock.elapsedRealtime() < until) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(500)
        }
        nowElapsed = SystemClock.elapsedRealtime()
    }
    LaunchedEffect(unlocking) {
        if (!unlocking) {
            entry = ""
        }
    }
    val remaining = (until - nowElapsed).coerceAtLeast(0L)
    val locked = remaining > 0L
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) {
                            interact()
                        }
                    }
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            unlocking -> {
                Text(
                    text = "解除しています",
                    color = Ink,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            locked -> {
                Text(
                    text = "2分間、入力できません",
                    color = Ink,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "あと ${formatRemaining(remaining)}",
                    color = Ink,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
            else -> {
                Text(
                    text = pinDots(entry),
                    color = Ink,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    letterSpacing = 8.sp,
                )
                PinRows(
                    onKey = { label ->
                        interact()
                        onTapSound()
                        when (label) {
                            "消去" -> entry = entry.dropLast(1)
                            "OK" -> {
                                if (entry.length != PinLength) {
                                    onBeep()
                                } else {
                                    when (controller.submitPin(entry)) {
                                        AwayPinResult.Wrong,
                                        AwayPinResult.Locked,
                                        -> {
                                            entry = ""
                                            onBeep()
                                        }
                                        AwayPinResult.Accepted -> Unit
                                    }
                                }
                            }
                            else -> if (entry.length < PinLength) {
                                entry += label
                            }
                        }
                    },
                    modifier = Modifier.padding(top = 28.dp),
                )
            }
        }
    }
}

@Composable
private fun PinRows(
    onKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PinLabels.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { label ->
                    PinKey(label = label, onClick = { onKey(label) })
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 148.dp, height = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(KeyColor)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Ink,
            fontSize = if (label.length == 1) 36.sp else 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private fun pinDots(entry: String): String {
    return (0 until PinLength).joinToString("  ") { index ->
        entry.getOrNull(index)?.toString() ?: "–"
    }
}

private fun formatRemaining(remainingMs: Long): String {
    val totalSec = ((remainingMs + 999L) / 1000L).toInt()
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val PinLength = 4
private const val PinIdleMs = 30_000L

private val PinLabels = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("消去", "0", "OK"),
)
