package app.smartdisplay.timer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smartdisplay.ui.Panel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@Composable
fun TimerPage(
    state: TimerUiState,
    now: LocalDateTime,
    onStart: (Long) -> Unit,
    onStop: () -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(5) }
    var seconds by remember { mutableIntStateOf(0) }
    val running = state.phase == TimerPhase.Running
    val ringing = state.phase == TimerPhase.Ringing
    val active = running || ringing
    val durationMillis = ((hours * 60L + minutes) * 60L + seconds) * 1000L
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Text(
            text = "タイマー",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                TimerActiveReadout(state = state, now = now, ringing = ringing)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeStepper(
                        value = hours,
                        unit = "時間",
                        onTapSound = onTapSound,
                        onStep = { delta -> hours = (hours + delta).coerceIn(0, 23) },
                        modifier = Modifier.weight(1f),
                    )
                    TimeStepper(
                        value = minutes,
                        unit = "分",
                        onTapSound = onTapSound,
                        onStep = { delta -> minutes = (minutes + delta).coerceIn(0, 59) },
                        modifier = Modifier.weight(1f),
                    )
                    TimeStepper(
                        value = seconds,
                        unit = "秒",
                        onTapSound = onTapSound,
                        onStep = { delta -> seconds = (seconds + delta).coerceIn(0, 59) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        TimerActionButton(
            label = if (active) "停止" else "開始",
            enabled = active || durationMillis > 0L,
            onClick = {
                onTapSound()
                if (active) {
                    onStop()
                } else {
                    onStart(durationMillis)
                }
            },
        )
    }
}

@Composable
private fun TimerActiveReadout(
    state: TimerUiState,
    now: LocalDateTime,
    ringing: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (ringing) {
                "時間になりました"
            } else {
                formatTimerRemaining(timerRemainingMillis(state.endEpochMillis, now))
            },
            color = Color.White,
            fontSize = if (ringing) 48.sp else 88.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
        if (!ringing) {
            Text(
                text = "残り",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 22.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TimeStepper(
    value: Int,
    unit: String,
    onStep: (Int) -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        RepeatStepButton(
            label = "＋",
            onTapSound = onTapSound,
            onStep = { onStep(1) },
        )
        Text(
            text = "%02d".format(value),
            color = Color.White,
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
        Text(
            text = unit,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 20.sp,
        )
        RepeatStepButton(
            label = "−",
            onTapSound = onTapSound,
            onStep = { onStep(-1) },
        )
    }
}

@Composable
private fun RepeatStepButton(
    label: String,
    onStep: () -> Unit,
    onTapSound: () -> Unit,
) {
    val step by rememberUpdatedState(onStep)
    val tap by rememberUpdatedState(onTapSound)
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        tap()
                        step()
                        coroutineScope {
                            val repeater = launch {
                                delay(350)
                                while (isActive) {
                                    step()
                                    delay(70)
                                }
                            }
                            tryAwaitRelease()
                            repeater.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TimerActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.06f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (enabled) 0.28f else 0.1f),
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.35f),
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun ClockHubPage(
    timerState: TimerUiState,
    alarmState: AlarmUiState,
    now: LocalDateTime,
    onOpenTimer: () -> Unit,
    onOpenAlarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "クロック",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        ClockHubRow(
            title = "タイマー",
            subtitle = timerHubSubtitle(timerState, now),
            onClick = onOpenTimer,
        )
        ClockHubRow(
            title = "アラーム",
            subtitle = alarmHubSubtitle(alarmState),
            onClick = onOpenAlarm,
        )
    }
}

@Composable
private fun ClockHubRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Panel(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun timerHubSubtitle(state: TimerUiState, now: LocalDateTime): String? {
    return when (state.phase) {
        TimerPhase.Idle -> null
        TimerPhase.Running -> "残り ${formatTimerRemaining(timerRemainingMillis(state.endEpochMillis, now))}"
        TimerPhase.Ringing -> "時間になりました"
    }
}

private fun alarmHubSubtitle(state: AlarmUiState): String? {
    return when (state.phase) {
        AlarmPhase.Idle -> null
        AlarmPhase.Armed -> formatAlarmTime(state.hour, state.minute)
        AlarmPhase.Ringing -> "時間になりました"
    }
}

@Composable
fun AlarmPage(
    state: AlarmUiState,
    onStart: (hour: Int, minute: Int) -> Unit,
    onStop: () -> Unit,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hours by remember { mutableIntStateOf(7) }
    var minutes by remember { mutableIntStateOf(0) }
    val active = state.phase == AlarmPhase.Armed || state.phase == AlarmPhase.Ringing
    val ringing = state.phase == AlarmPhase.Ringing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 28.dp),
    ) {
        Text(
            text = "アラーム",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (ringing) {
                            "時間になりました"
                        } else {
                            formatAlarmTime(state.hour, state.minute)
                        },
                        color = Color.White,
                        fontSize = if (ringing) 48.sp else 88.sp,
                        fontWeight = FontWeight.Light,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                    if (!ringing) {
                        Text(
                            text = "設定中",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 22.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeStepper(
                        value = hours,
                        unit = "時",
                        onTapSound = onTapSound,
                        onStep = { delta -> hours = (hours + delta).mod(24) },
                        modifier = Modifier.weight(1f),
                    )
                    TimeStepper(
                        value = minutes,
                        unit = "分",
                        onTapSound = onTapSound,
                        onStep = { delta -> minutes = (minutes + delta).mod(60) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        TimerActionButton(
            label = if (active) "停止" else "開始",
            enabled = true,
            onClick = {
                onTapSound()
                if (active) {
                    onStop()
                } else {
                    onStart(hours, minutes)
                }
            },
        )
    }
}
