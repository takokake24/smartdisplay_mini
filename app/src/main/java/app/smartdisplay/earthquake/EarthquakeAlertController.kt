package app.smartdisplay.earthquake

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.audio.EffectPlayer
import app.smartdisplay.audio.MusicVolumeBoost
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.notification.NotificationCategoryEew
import app.smartdisplay.notification.NotificationCategoryEarthquakeIntensity
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.awaitCrisisExclusiveHold
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.DefaultSpeechRate
import app.smartdisplay.speech.SpeechSpeaker
import app.smartdisplay.util.FetchFailureNotifier
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.NoInternetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class EarthquakeAlertController(
    context: Context,
    private val settings: EarthquakeSettings,
    private val notifications: NotificationCenter,
    private val speaker: SpeechSpeaker,
    private val effects: EffectPlayer,
    private val announcementGate: AnnouncementGate,
    private val chimeController: HourlyChimeController,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val alertMutex = Mutex()
    private var eewJob: Job? = null
    private var monitorJob: Job? = null
    private val repository = EarthquakeRepository()
    private val handledEewEventIds = linkedSetOf<String>()
    private val intensityByQuakeKey = mutableMapOf<String, Int>()

    private val _phase = MutableStateFlow(EarthquakeAlertPhase.Idle)
    val phase: StateFlow<EarthquakeAlertPhase> = _phase.asStateFlow()

    private val _forceCloseMenu = MutableStateFlow(0)
    val forceCloseMenu: StateFlow<Int> = _forceCloseMenu.asStateFlow()

    private val _lastPolledAt = MutableStateFlow<LocalDateTime?>(null)
    val lastPolledAt: StateFlow<LocalDateTime?> = _lastPolledAt.asStateFlow()

    private val _lastFetchFailures = MutableStateFlow<List<String>>(emptyList())
    val lastFetchFailures: StateFlow<List<String>> = _lastFetchFailures.asStateFlow()

    fun start() {
        if (monitorJob?.isActive == true) {
            return
        }
        monitorJob = scope.launch {
            runCatching { seedHandledFromHistory() }
            while (isActive) {
                runCatching { pollOnce() }
                delay(PollIntervalMs)
            }
        }
    }

    private suspend fun seedHandledFromHistory() {
        val eews = withContext(Dispatchers.IO) {
            repository.fetchRecentEew(limit = 10)
        }
        eews.forEach { event ->
            handledEewEventIds.add(event.eventId.ifBlank { event.id })
        }
        val quakes = withContext(Dispatchers.IO) {
            repository.fetchRecentQuakes(limit = 10)
        }
        quakes.forEach { event ->
            intensityByQuakeKey[event.earthquakeTime.format(QuakeKeyFormatter)] =
                maxOf(
                    intensityByQuakeKey[event.earthquakeTime.format(QuakeKeyFormatter)] ?: -1,
                    event.kodairaScale,
                )
        }
    }

    fun release() {
        eewJob?.cancel()
        monitorJob?.cancel()
        scope.cancel()
    }

    fun triggerEewTest() {
        handleEew(
            EewEvent(
                id = "test-${System.currentTimeMillis()}",
                eventId = "TEST-${System.currentTimeMillis()}",
                serial = "1",
                cancelled = false,
                isTest = true,
                originTime = LocalDateTime.now(),
                areas = listOf(
                    EewArea(
                        pref = "東京",
                        name = "多摩東部",
                        scaleFrom = 40,
                        scaleTo = 45,
                    ),
                ),
            ),
            force = true,
        )
    }

    private var intensityTestCursor = 0

    fun triggerIntensityTest(scale: Int? = null) {
        val nextScale = scale ?: IntensityTestScales[
            intensityTestCursor % IntensityTestScales.size,
        ]
        if (scale == null) {
            intensityTestCursor += 1
        }
        handleIntensity(
            QuakeIntensityEvent(
                id = "test-intensity-${System.currentTimeMillis()}",
                earthquakeTime = LocalDateTime.now().withSecond(0).withNano(0),
                hypocenterName = "テスト震源",
                kodairaScale = nextScale,
            ),
            force = true,
        )
    }

    fun triggerIntensityUpdateTest() {
        val quakeTime = LocalDateTime.now().withSecond(0).withNano(0)
        handleIntensity(
            QuakeIntensityEvent(
                id = "test-intensity-base-${System.currentTimeMillis()}",
                earthquakeTime = quakeTime,
                hypocenterName = "テスト震源",
                kodairaScale = 30,
            ),
            force = true,
        )
        scope.launch {
            delay(2_500L)
            handleIntensity(
                QuakeIntensityEvent(
                    id = "test-intensity-update-${System.currentTimeMillis()}",
                    earthquakeTime = quakeTime,
                    hypocenterName = "テスト震源",
                    kodairaScale = 50,
                ),
                force = true,
            )
        }
    }

    private suspend fun pollOnce() {
        if (!InternetConnectivity.hasInternet()) {
            return
        }
        val eewResult = runCatching {
            withContext(Dispatchers.IO) {
                repository.fetchRecentEew()
            }
        }
        eewResult.getOrDefault(emptyList()).asReversed().forEach { handleEew(it, force = false) }

        val quakeResult = runCatching {
            withContext(Dispatchers.IO) {
                repository.fetchRecentQuakes()
            }
        }
        quakeResult.getOrDefault(emptyList()).asReversed().forEach { handleIntensity(it, force = false) }

        (eewResult.exceptionOrNull() as? CancellationException)?.let { throw it }
        (quakeResult.exceptionOrNull() as? CancellationException)?.let { throw it }
        if (!InternetConnectivity.hasInternet()) {
            return
        }
        val failures = buildList {
            if (isRealFetchFailure(eewResult)) {
                add("緊急地震速報")
            }
            if (isRealFetchFailure(quakeResult)) {
                add("地震情報")
            }
        }
        _lastFetchFailures.value = failures
        if (eewResult.isSuccess) {
            FetchFailureNotifier.clear(listOf("緊急地震速報"))
        }
        if (quakeResult.isSuccess) {
            FetchFailureNotifier.clear(listOf("地震情報"))
        }
        FetchFailureNotifier.report(failures)
        if (eewResult.isSuccess || quakeResult.isSuccess) {
            _lastPolledAt.value = LocalDateTime.now()
        }
    }

    private fun isRealFetchFailure(result: Result<*>): Boolean {
        val error = result.exceptionOrNull() ?: return false
        return error !is NoInternetException && error !is CancellationException
    }

    private fun handleEew(event: EewEvent, force: Boolean) {
        if (event.cancelled) {
            return
        }
        if (!force && event.isTest) {
            return
        }
        val covers = event.areas.any(::eewAreaCoversKodaira)
        if (!covers) {
            return
        }
        val key = event.eventId.ifBlank { event.id }
        if (!force && key in handledEewEventIds) {
            return
        }
        handledEewEventIds.add(key)
        while (handledEewEventIds.size > 40) {
            handledEewEventIds.remove(handledEewEventIds.first())
        }
        eewJob?.cancel()
        eewJob = scope.launch {
            runEewSequence()
        }
    }

    private fun handleIntensity(event: QuakeIntensityEvent, force: Boolean) {
        if (event.kodairaScale <= 0) {
            return
        }
        val key = event.earthquakeTime.format(QuakeKeyFormatter)
        val previous = intensityByQuakeKey[key]
        if (!force && previous != null && event.kodairaScale <= previous) {
            return
        }
        val updated = previous != null && event.kodairaScale > previous
        intensityByQuakeKey[key] = event.kodairaScale
        val timeSpeech = colonTime(event.earthquakeTime)
        val scaleSpeech = intensitySpeech(event.kodairaScale)
        val speech = if (updated) {
            "地震情報です。${timeSpeech}に発生した地震は、震度${scaleSpeech}に更新されました。"
        } else {
            "地震情報です。${timeSpeech}に発生した地震は、震度${scaleSpeech}です。"
        }
        notifications.post(
            category = NotificationCategoryEarthquakeIntensity,
            title = "地震情報",
            body = "震度\n${intensityDisplayGlyph(event.kodairaScale)}",
            speechText = speech,
            earthquakeIntensityScale = event.kodairaScale,
            earthquakeOccurredAt = event.earthquakeTime,
            alert = true,
            addToHistory = true,
            soundEffect = NotificationSoundEffect.Alert1,
            priority = true,
            allowDuringEarthquakeExclusive = true,
        )
    }

    private suspend fun runEewSequence() {
        alertMutex.withLock {
            try {
                val startedAt = SystemClock.elapsedRealtime()
                _forceCloseMenu.value = _forceCloseMenu.value + 1
                _phase.value = EarthquakeAlertPhase.FullScreen
                notifications.abortForEarthquakePriority()
                notifications.setEarthquakeExclusive(true)
                chimeController.interruptPlayback()

                MusicVolumeBoost.raise(appContext, settings.eewVolumeRatio)
                try {
                    announcementGate.withPriorityLock(
                        interrupt = {
                            speaker.stop()
                            chimeController.interruptPlayback()
                        },
                    ) {
                        // shouldContinueSpeech は優先ロック保持中だと false になるためここでは見ない
                        speaker.setSpeechRate(EewSpeechRate)
                        try {
                            repeat(2) {
                                runCatching { effects.playAlert2() }
                                repeat(2) {
                                    speaker.speakNow(EewSpeechText)
                                }
                            }
                        } finally {
                            speaker.setSpeechRate(DefaultSpeechRate)
                        }
                    }
                } finally {
                    MusicVolumeBoost.restore(appContext)
                }

                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val remainToHalf = FullScreenDurationMs - elapsed
                if (remainToHalf > 0) {
                    delay(remainToHalf)
                }

                _phase.value = EarthquakeAlertPhase.Banner
                notifications.post(
                    category = NotificationCategoryEew,
                    title = "緊急地震速報",
                    body = "強い揺れに警戒してください",
                    speechText = "",
                    alert = true,
                    addToHistory = false,
                    soundEffect = NotificationSoundEffect.None,
                    priority = true,
                    allowDuringEarthquakeExclusive = true,
                    skipSpeech = true,
                )

                awaitCrisisBannerHold()
                if (notifications.isEarthquakeExclusive) {
                    notifications.finishEarthquakeExclusiveBanner()
                    notifications.setEarthquakeExclusive(false)
                }
                _phase.value = EarthquakeAlertPhase.Idle
            } finally {
                // 表示解除（または中断）後に時報スケジュールを戻す
                if (_phase.value != EarthquakeAlertPhase.Idle) {
                    notifications.finishEarthquakeExclusiveBanner()
                    notifications.setEarthquakeExclusive(false)
                    _phase.value = EarthquakeAlertPhase.Idle
                }
                chimeController.resumeAfterInterrupt()
            }
        }
    }

    /** バナー表示を最大 [BannerDurationMs] 待つ。手動クローズで排他が解けたらすぐ終わる。 */
    private suspend fun awaitCrisisBannerHold() {
        awaitCrisisExclusiveHold(notifications, BannerDurationMs)
    }

    private fun colonTime(time: LocalDateTime): String {
        return "${time.hour}:${time.minute.toString().padStart(2, '0')}"
    }

    companion object {
        private const val PollIntervalMs = 2_000L
        private const val FullScreenDurationMs = 30_000L
        private const val BannerDurationMs = 60_000L
        private const val EewSpeechRate = 1.35f
        private const val EewSpeechText =
            "緊急地震速報が発令されました。強い揺れに警戒してください。"
        private val QuakeKeyFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        private val IntensityTestScales = listOf(10, 20, 30, 40, 45, 50, 55, 60, 70)
    }
}

@Composable
fun rememberEarthquakeAlertController(
    settings: EarthquakeSettings,
    notifications: NotificationCenter,
    speaker: SpeechSpeaker,
    effects: EffectPlayer,
    announcementGate: AnnouncementGate,
    chimeController: HourlyChimeController,
): EarthquakeAlertController {
    val context = LocalContext.current
    val controller = remember(
        settings,
        notifications,
        speaker,
        effects,
        announcementGate,
        chimeController,
    ) {
        EarthquakeAlertController(
            context = context.applicationContext,
            settings = settings,
            notifications = notifications,
            speaker = speaker,
            effects = effects,
            announcementGate = announcementGate,
            chimeController = chimeController,
        )
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.release() }
    }
    return controller
}
