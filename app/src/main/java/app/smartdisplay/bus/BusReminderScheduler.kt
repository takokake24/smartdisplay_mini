package app.smartdisplay.bus

import app.smartdisplay.notification.NotificationCategoryBus
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

sealed class BusReminderToggleResult {
    data object Ok : BusReminderToggleResult()
    data object PastDate : BusReminderToggleResult()
    data object FutureDate : BusReminderToggleResult()
    data object Departed : BusReminderToggleResult()
}

class BusReminderScheduler(
    private val notifications: NotificationCenter,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, List<Job>>()

    private val _scheduledTrips = MutableStateFlow<List<BusTrip>>(emptyList())
    val scheduledTrips: StateFlow<List<BusTrip>> = _scheduledTrips.asStateFlow()

    val scheduledTripIds: Set<String>
        get() = _scheduledTrips.value.map { it.id }.toSet()

    fun isScheduled(tripId: String): Boolean = _scheduledTrips.value.any { it.id == tripId }

    fun toggle(trip: BusTrip): BusReminderToggleResult {
        if (isScheduled(trip.id)) {
            cancel(trip.id)
            return BusReminderToggleResult.Ok
        }
        return schedule(trip)
    }

    fun schedule(trip: BusTrip): BusReminderToggleResult {
        cancel(trip.id)
        val now = LocalDateTime.now()
        val today = busServiceDate(now)
        val tripDate = busServiceDate(trip.departureAt)
        when {
            tripDate.isBefore(today) -> return BusReminderToggleResult.PastDate
            tripDate.isAfter(today) -> return BusReminderToggleResult.FutureDate
            !trip.departureAt.isAfter(now) -> return BusReminderToggleResult.Departed
        }
        val pendingOffsets = ReminderOffsets.filter { minutesBefore ->
            trip.departureAt.minusMinutes(minutesBefore.toLong()).isAfter(now)
        }
        val created = pendingOffsets.map { minutesBefore ->
            val fireAt = trip.departureAt.minusMinutes(minutesBefore.toLong())
            val waitMs = Duration.between(now, fireAt).toMillis().coerceAtLeast(0L)
            scope.launch {
                delay(waitMs)
                if (!isScheduled(trip.id)) {
                    return@launch
                }
                val spokenDestination = trip.speechDestinationDisplayText
                val body =
                    "${trip.company.displayName} ${trip.originStop}発 ${trip.routeName}系統 ${spokenDestination}の発車まであと${minutesBefore}分です"
                val speech = speechBusReminderText(
                    companyName = trip.company.displayName,
                    originStop = trip.originStop,
                    routeName = trip.routeName,
                    destination = spokenDestination,
                    minutesBefore = minutesBefore,
                )
                notifications.post(
                    category = NotificationCategoryBus,
                    title = "バス発車案内",
                    body = body,
                    speechText = speech,
                    soundEffect = NotificationSoundEffect.Notice,
                )
            }
        }
        jobs[trip.id] = created
        _scheduledTrips.update { current ->
            (current.filterNot { it.id == trip.id } + trip)
                .sortedBy { it.departureAt }
        }
        return BusReminderToggleResult.Ok
    }

    fun cancel(tripId: String) {
        jobs.remove(tripId)?.forEach { it.cancel() }
        _scheduledTrips.update { list -> list.filterNot { it.id == tripId } }
    }

    /** 発車時刻を過ぎた乗車通知を一覧から外す。 */
    fun pruneExpired(now: LocalDateTime = LocalDateTime.now()) {
        val expiredIds = _scheduledTrips.value
            .filter { !it.departureAt.isAfter(now) }
            .map { it.id }
        if (expiredIds.isEmpty()) {
            return
        }
        expiredIds.forEach { id ->
            jobs.remove(id)?.forEach { job -> job.cancel() }
        }
        _scheduledTrips.update { list ->
            list.filter { it.departureAt.isAfter(now) }
        }
    }

    companion object {
        private val ReminderOffsets = listOf(15, 10, 5, 3)
    }
}
