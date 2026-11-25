package app.smartdisplay.steps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import app.smartdisplay.ui.Panel
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

@Composable
fun StepsPage(
    today: LocalDate,
    onTapSound: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember { StepsRepository(context) }
    var permissionGranted by remember { mutableStateOf(false) }
    var permissionChecked by remember { mutableStateOf(false) }
    var weekStart by remember(today) { mutableStateOf(weekStartMonday(today)) }
    var week by remember { mutableStateOf<StepWeek?>(null) }
    var failed by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        permissionGranted = StepsAccess.readPermission in granted
        permissionChecked = true
    }
    var status by remember { mutableIntStateOf(StepsAccess.sdkStatus(context)) }
    var resumeToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = StepsAccess.sdkStatus(context)
                resumeToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status, resumeToken) {
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            permissionGranted = false
            permissionChecked = true
            return@LaunchedEffect
        }
        permissionGranted = StepsAccess.hasPermission(context)
        permissionChecked = true
    }
    LaunchedEffect(permissionGranted, weekStart, today) {
        if (!permissionGranted) {
            week = null
            failed = false
            return@LaunchedEffect
        }
        failed = false
        week = null
        try {
            week = repository.loadWeek(weekStart, today)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "歩数計",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "この端末のヘルスコネクトに記録された歩数です。",
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
        )
        when {
            !permissionChecked -> {
                Text(
                    text = "読み込んでいます",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 18.sp,
                )
            }
            status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                StepsAction(
                    title = "ヘルスコネクトをインストール",
                    subtitle = "歩数を読むには、ヘルスコネクトが必要です",
                    onClick = {
                        onTapSound()
                        StepsAccess.openInstallPage(context)
                    },
                )
            }
            status != HealthConnectClient.SDK_AVAILABLE -> {
                Text(
                    text = "この端末ではヘルスコネクトの歩数を利用できません",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 18.sp,
                )
            }
            !permissionGranted -> {
                StepsAction(
                    title = "歩数の読み取りを許可",
                    subtitle = "ヘルスコネクトに記録された歩数を表示します",
                    onClick = {
                        onTapSound()
                        permissionLauncher.launch(StepsAccess.permissionRequest())
                    },
                )
            }
            else -> {
                WeekPager(
                    weekStart = weekStart,
                    today = today,
                    totalSteps = week
                        ?.takeIf { it.start == weekStart }
                        ?.days
                        ?.lastOrNull { it.cumulative != null }
                        ?.cumulative,
                    onPrevious = {
                        onTapSound()
                        weekStart = weekStart.minusWeeks(1)
                    },
                    onNext = {
                        onTapSound()
                        weekStart = weekStart.plusWeeks(1)
                    },
                )
                when {
                    failed -> {
                        Text(
                            text = "歩数を取得できませんでした",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 18.sp,
                        )
                    }
                    week == null -> {
                        Text(
                            text = "読み込んでいます",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 18.sp,
                        )
                    }
                    else -> {
                        week?.days?.forEach { day ->
                            StepDayRow(day = day, today = today)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekPager(
    weekStart: LocalDate,
    today: LocalDate,
    totalSteps: Long?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val current = weekStart == weekStartMonday(today)
    val canGoBack = weekStart > LocalDate.of(2015, 1, 5)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WeekNavLabel(
            text = "前の週",
            enabled = canGoBack,
            onClick = onPrevious,
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = weekRangeLabel(weekStart, today),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
            if (totalSteps != null) {
                Text(
                    text = "累計 ${formatStepCount(totalSteps)}",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        WeekNavLabel(
            text = "次の週",
            enabled = !current,
            onClick = onNext,
        )
    }
}

@Composable
private fun WeekNavLabel(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Panel(
        onClick = if (enabled) onClick else null,
        modifier = Modifier
            .wrapContentWidth()
            .alpha(if (enabled) 1f else 0.35f),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StepDayRow(
    day: StepDay,
    today: LocalDate,
) {
    val stepsText = day.steps?.let(::formatStepCount) ?: "—"
    val cumulativeText = day.cumulative?.let { "累計 ${formatStepCount(it)}" } ?: "累計 —"
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayHeading(day.date, today),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stepsText,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = cumulativeText,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StepsAction(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Panel(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
