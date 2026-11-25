package app.smartdisplay.steps

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.smartdisplay.notification.NotificationCategorySteps
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.util.PreparedFetch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class StepDay(
    val date: LocalDate,
    val steps: Long?,
    val cumulative: Long?,
)

data class StepWeek(
    val start: LocalDate,
    val days: List<StepDay>,
)

fun weekStartMonday(date: LocalDate): LocalDate {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

fun stepWeekFromDailyCounts(
    weekStart: LocalDate,
    today: LocalDate,
    counts: Map<LocalDate, Long>,
): StepWeek {
    var running = 0L
    val days = (0 until 7).map { offset ->
        val date = weekStart.plusDays(offset.toLong())
        if (date > today) {
            StepDay(date = date, steps = null, cumulative = null)
        } else {
            val steps = counts[date] ?: 0L
            running += steps
            StepDay(date = date, steps = steps, cumulative = running)
        }
    }
    return StepWeek(start = weekStart, days = days)
}

fun stepsSpeech(today: Long, week: Long): String {
    return "今日歩いた歩数は、${today}歩でした。今週は合計で、${week}歩歩きました。"
}

fun formatStepCount(steps: Long): String {
    val number = "%,d".format(Locale.JAPAN, steps)
    return "${number}歩"
}

fun weekRangeLabel(start: LocalDate, today: LocalDate): String {
    val end = start.plusDays(6)
    return "${dayLabel(start, today.year)} – ${dayLabel(end, today.year)}"
}

fun dayHeading(date: LocalDate, today: LocalDate): String {
    val weekday = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "月"
        DayOfWeek.TUESDAY -> "火"
        DayOfWeek.WEDNESDAY -> "水"
        DayOfWeek.THURSDAY -> "木"
        DayOfWeek.FRIDAY -> "金"
        DayOfWeek.SATURDAY -> "土"
        DayOfWeek.SUNDAY -> "日"
    }
    val dateText = date.format(DayFormatter)
    val todayMark = if (date == today) "（今日）" else ""
    return "$weekday  $dateText$todayMark"
}

private fun dayLabel(date: LocalDate, thisYear: Int): String {
    val year = if (date.year == thisYear) "" else "${date.year}年"
    return "$year${date.monthValue}月${date.dayOfMonth}日"
}

private val DayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日", Locale.JAPAN)

object StepsAccess {
    private const val ProviderPackage = "com.google.android.apps.healthdata"

    val readPermission: String
        get() = HealthPermission.getReadPermission(StepsRecord::class)

    fun sdkStatus(context: Context): Int {
        return HealthConnectClient.getSdkStatus(context, ProviderPackage)
    }

    fun isSdkAvailable(context: Context): Boolean {
        return sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasPermission(context: Context): Boolean {
        if (!isSdkAvailable(context)) {
            return false
        }
        return runCatching {
            val granted = HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
            readPermission in granted
        }.getOrDefault(false)
    }

    fun permissionRequest(): Set<String> = setOf(readPermission)

    fun openInstallPage(context: Context) {
        val market = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(
                "market://details?id=$ProviderPackage&url=healthconnect%3A%2F%2Fonboarding",
            )
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(market) }.isSuccess) {
            return
        }
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$ProviderPackage"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(web) }
    }
}

class StepsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun loadWeek(weekStart: LocalDate, today: LocalDate): StepWeek {
        val counts = readDailyCounts(weekStart, weekStart.plusWeeks(1))
        return stepWeekFromDailyCounts(weekStart, today, counts)
    }

    private suspend fun readDailyCounts(
        start: LocalDate,
        endExclusive: LocalDate,
    ): Map<LocalDate, Long> = withContext(Dispatchers.IO) {
        val client = HealthConnectClient.getOrCreate(appContext)
        val response = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    start.atStartOfDay(),
                    endExclusive.atStartOfDay(),
                ),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        buildMap {
            for (row in response) {
                val date = row.startTime.toLocalDate()
                val steps = row.result[StepsRecord.COUNT_TOTAL] ?: 0L
                this[date] = (this[date] ?: 0L) + steps
            }
        }
    }
}

/**
 * 23:30 の時報のあと、明日の天気の前に今日と今週の歩数を読む。
 * 週は月曜始まり。
 */
class StepsAnnouncer(
    context: Context,
    private val notifications: NotificationCenter,
) {
    private val appContext = context.applicationContext
    private val repository = StepsRepository(appContext)
    private val prepared = PreparedFetch<StepsPrepared>()

    suspend fun onChimeFinished(time: LocalDateTime) {
        if (time.hour != 23 || time.minute != 30) {
            return
        }
        postAnnouncement(time, reportUnavailable = false)
    }

    /** 時報の鳴り始めに呼ぶ。案内の時点では、ここで始めた取得を使う。 */
    fun prepare(now: LocalDateTime) {
        prepared.start(now.toLocalDate()) { loadPrepared(now) }
    }

    suspend fun postAnnouncement(
        now: LocalDateTime = LocalDateTime.now(),
        reportUnavailable: Boolean = true,
    ) {
        val loaded = prepared.take(now.toLocalDate()) ?: loadPrepared(now)
        val ready = when (loaded) {
            StepsPrepared.SdkUnavailable -> {
                if (reportUnavailable) {
                    notifications.postReadoutFailure(
                        title = "歩数計",
                        message = "この端末ではヘルスコネクトの歩数を利用できません。",
                    )
                }
                return
            }
            StepsPrepared.NoPermission -> {
                if (reportUnavailable) {
                    notifications.postReadoutFailure(
                        title = "歩数計",
                        message = "歩数を取得するには、アプリの権限を変更してください。",
                    )
                }
                return
            }
            StepsPrepared.Failed -> {
                notifications.postReadoutFailure(
                    title = "歩数計",
                    message = "歩数の取得に失敗しました。",
                )
                return
            }
            is StepsPrepared.Ready -> loaded
        }
        notifications.post(
            category = NotificationCategorySteps,
            title = "歩数計",
            body = ready.speech,
            speechText = ready.speech,
            stepsToday = ready.todaySteps,
            stepsWeek = ready.weekSteps,
            alert = true,
            addToHistory = false,
            soundEffect = NotificationSoundEffect.None,
            priority = true,
        )
    }

    private suspend fun loadPrepared(now: LocalDateTime): StepsPrepared {
        if (!StepsAccess.isSdkAvailable(appContext)) {
            return StepsPrepared.SdkUnavailable
        }
        if (!StepsAccess.hasPermission(appContext)) {
            return StepsPrepared.NoPermission
        }
        val today = now.toLocalDate()
        return try {
            val week = repository.loadWeek(weekStartMonday(today), today)
            val todaySteps = week.days.firstOrNull { it.date == today }?.steps ?: 0L
            val weekSteps = week.days.lastOrNull { it.cumulative != null }?.cumulative ?: 0L
            StepsPrepared.Ready(
                todaySteps = todaySteps,
                weekSteps = weekSteps,
                speech = stepsSpeech(todaySteps, weekSteps),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            StepsPrepared.NoPermission
        } catch (_: Exception) {
            StepsPrepared.Failed
        }
    }
}

private sealed class StepsPrepared {
    data class Ready(
        val todaySteps: Long,
        val weekSteps: Long,
        val speech: String,
    ) : StepsPrepared()

    data object SdkUnavailable : StepsPrepared()
    data object NoPermission : StepsPrepared()
    data object Failed : StepsPrepared()
}

@Composable
fun rememberStepsAnnouncer(
    notifications: NotificationCenter,
): StepsAnnouncer {
    val context = LocalContext.current.applicationContext
    return remember(notifications, context) {
        StepsAnnouncer(
            context = context,
            notifications = notifications,
        )
    }
}
