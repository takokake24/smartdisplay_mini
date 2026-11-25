package app.smartdisplay.warning

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.audio.EffectPlayer
import app.smartdisplay.audio.MusicVolumeBoost
import app.smartdisplay.earthquake.EarthquakeSettings
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.notification.NotificationCategoryWeatherWarning
import app.smartdisplay.notification.NotificationCategoryWeatherWarningCritical
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.awaitCrisisExclusiveHold
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
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

enum class WeatherWarningAlertPhase {
    Idle,
    FullScreen,
    Banner,
}

data class WeatherWarningCrisisUi(
    val title: String,
    val message: String,
)

class WeatherWarningController(
    context: Context,
    private val earthquakeSettings: EarthquakeSettings,
    private val notifications: NotificationCenter,
    private val speaker: SpeechSpeaker,
    private val effects: EffectPlayer,
    private val announcementGate: AnnouncementGate,
    private val chimeController: HourlyChimeController,
    private val client: JmaWarningClient = JmaWarningClient(),
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val alertMutex = Mutex()
    private var monitorJob: Job? = null
    private var crisisJob: Job? = null
    private var seeded = false
    private var previousByCode = linkedMapOf<String, WeatherWarningItem>()

    private val _activeWarnings = MutableStateFlow<List<WeatherWarningItem>>(emptyList())
    val activeWarnings: StateFlow<List<WeatherWarningItem>> = _activeWarnings.asStateFlow()

    private val _lastFetchedAt = MutableStateFlow<LocalDateTime?>(null)
    val lastFetchedAt: StateFlow<LocalDateTime?> = _lastFetchedAt.asStateFlow()

    private val _lastFetchFailed = MutableStateFlow(false)
    val lastFetchFailed: StateFlow<Boolean> = _lastFetchFailed.asStateFlow()

    private val _phase = MutableStateFlow(WeatherWarningAlertPhase.Idle)
    val phase: StateFlow<WeatherWarningAlertPhase> = _phase.asStateFlow()

    private val _crisisUi = MutableStateFlow(
        WeatherWarningCrisisUi(
            title = "気象警報",
            message = "今後の状況に警戒してください",
        ),
    )
    val crisisUi: StateFlow<WeatherWarningCrisisUi> = _crisisUi.asStateFlow()

    private val _forceCloseMenu = MutableStateFlow(0)
    val forceCloseMenu: StateFlow<Int> = _forceCloseMenu.asStateFlow()

    fun start() {
        if (monitorJob?.isActive == true) {
            return
        }
        monitorJob = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                    .onSuccess {
                        if (!InternetConnectivity.hasInternet()) {
                            return@onSuccess
                        }
                        _lastFetchFailed.value = false
                        FetchFailureNotifier.clear(listOf("気象警報"))
                    }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                        if (error is NoInternetException || !InternetConnectivity.hasInternet()) {
                            return@onFailure
                        }
                        _lastFetchFailed.value = true
                        FetchFailureNotifier.report(listOf("気象警報"))
                    }
                delay(PollIntervalMs)
            }
        }
    }

    fun release() {
        crisisJob?.cancel()
        monitorJob?.cancel()
        scope.cancel()
    }

    fun triggerTestIssue(severity: WeatherWarningSeverity) {
        val item = testItem(severity)
        when (severity) {
            WeatherWarningSeverity.Special,
            WeatherWarningSeverity.Danger,
            -> startCriticalSequence(item)
            WeatherWarningSeverity.Warning,
            WeatherWarningSeverity.Advisory,
            -> postNormalIssue(item)
        }
    }

    fun triggerTestCancel(severity: WeatherWarningSeverity = WeatherWarningSeverity.Advisory) {
        postCancel(testItem(severity))
    }

    private suspend fun pollOnce() {
        if (!InternetConnectivity.hasInternet()) {
            return
        }
        val fetched = withContext(Dispatchers.IO) {
            client.fetchKodairaWarnings()
        }
        _lastFetchedAt.value = LocalDateTime.now()
        val next = linkedMapOf<String, WeatherWarningItem>()
        fetched.forEach { next[it.code] = it }
        _activeWarnings.value = fetched

        if (!seeded) {
            previousByCode.clear()
            previousByCode.putAll(next)
            seeded = true
            return
        }

        val previous = previousByCode.toMap()
        val added = next.keys - previous.keys
        val removed = previous.keys - next.keys
        previousByCode.clear()
        previousByCode.putAll(next)

        removed.mapNotNull { previous[it] }.forEach { postCancel(it) }

        val addedItems = added.mapNotNull { next[it] }
            .sortedByDescending { it.severity.ordinal }
        addedItems.forEach { item ->
            when (item.severity) {
                WeatherWarningSeverity.Special,
                WeatherWarningSeverity.Danger,
                -> startCriticalSequence(item)
                WeatherWarningSeverity.Warning,
                WeatherWarningSeverity.Advisory,
                -> postNormalIssue(item)
            }
        }
    }

    private fun postNormalIssue(item: WeatherWarningItem) {
        val speech = buildWeatherWarningIssueSpeech(item)
        notifications.post(
            category = NotificationCategoryWeatherWarning,
            title = item.name,
            body = issueBody(item),
            speechText = speech,
            alert = true,
            addToHistory = true,
            soundEffect = NotificationSoundEffect.Alert1,
            priority = false,
        )
    }

    private fun postCancel(item: WeatherWarningItem) {
        notifications.post(
            category = NotificationCategoryWeatherWarning,
            title = "${item.name} 解除",
            body = "${item.name}が解除されました",
            speechText = buildWeatherWarningCancelSpeech(item),
            alert = true,
            addToHistory = true,
            soundEffect = NotificationSoundEffect.Notice,
            priority = false,
        )
    }

    private fun startCriticalSequence(item: WeatherWarningItem) {
        crisisJob?.cancel()
        crisisJob = scope.launch {
            runCriticalSequence(item)
        }
    }

    private suspend fun runCriticalSequence(item: WeatherWarningItem) {
        alertMutex.withLock {
            try {
                val speech = buildWeatherWarningIssueSpeech(item)
                val message = when (item.severity) {
                    WeatherWarningSeverity.Special,
                    WeatherWarningSeverity.Danger,
                    -> "今後の状況に警戒してください"
                    else -> "十分ご注意ください"
                }
                _crisisUi.value = WeatherWarningCrisisUi(
                    title = item.name,
                    message = message,
                )
                val startedAt = SystemClock.elapsedRealtime()
                _forceCloseMenu.value = _forceCloseMenu.value + 1
                _phase.value = WeatherWarningAlertPhase.FullScreen
                notifications.abortForEarthquakePriority()
                notifications.setEarthquakeExclusive(true)
                chimeController.interruptPlayback()

                MusicVolumeBoost.raise(appContext, earthquakeSettings.eewVolumeRatio)
                try {
                    announcementGate.withPriorityLock(
                        interrupt = {
                            speaker.stop()
                            chimeController.interruptPlayback()
                        },
                    ) {
                        // 警報音のあと読み上げを1回。このセットを2回行う
                        repeat(2) {
                            runCatching { effects.playAlert2() }
                            speaker.speakNow(speech)
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

                _phase.value = WeatherWarningAlertPhase.Banner
                notifications.post(
                    category = NotificationCategoryWeatherWarningCritical,
                    title = item.name,
                    body = message,
                    speechText = "",
                    alert = true,
                    addToHistory = true,
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
                _phase.value = WeatherWarningAlertPhase.Idle
            } finally {
                if (_phase.value != WeatherWarningAlertPhase.Idle) {
                    notifications.finishEarthquakeExclusiveBanner()
                    notifications.setEarthquakeExclusive(false)
                    _phase.value = WeatherWarningAlertPhase.Idle
                }
                chimeController.resumeAfterInterrupt()
            }
        }
    }

    /** バナー表示を最大 [BannerDurationMs] 待つ。手動クローズで排他が解けたらすぐ終わる。 */
    private suspend fun awaitCrisisBannerHold() {
        awaitCrisisExclusiveHold(notifications, BannerDurationMs)
    }

    private fun issueBody(item: WeatherWarningItem): String {
        val detail = item.detailTexts.joinToString("、")
        return buildString {
            append(item.name)
            append("が発令されました")
            if (detail.isNotBlank()) {
                append('\n')
                append(detail)
            }
        }
    }

    companion object {
        private const val PollIntervalMs = 60_000L
        private const val FullScreenDurationMs = 30_000L
        private const val BannerDurationMs = 60_000L

        private fun testItem(severity: WeatherWarningSeverity): WeatherWarningItem {
            val kind = when (severity) {
                WeatherWarningSeverity.Special -> weatherWarningKindOf("33")!!
                WeatherWarningSeverity.Danger -> weatherWarningKindOf("43")!!
                WeatherWarningSeverity.Warning -> weatherWarningKindOf("03")!!
                WeatherWarningSeverity.Advisory -> weatherWarningKindOf("10")!!
            }
            return WeatherWarningItem(
                kind = kind,
                status = "発表",
                reportAt = LocalDateTime.now(),
                detailTexts = when (severity) {
                    WeatherWarningSeverity.Advisory -> listOf("雷を伴う")
                    else -> emptyList()
                },
            )
        }
    }
}

@Composable
fun rememberWeatherWarningController(
    earthquakeSettings: EarthquakeSettings,
    notifications: NotificationCenter,
    speaker: SpeechSpeaker,
    effects: EffectPlayer,
    announcementGate: AnnouncementGate,
    chimeController: HourlyChimeController,
): WeatherWarningController {
    val context = LocalContext.current
    val controller = remember(
        earthquakeSettings,
        notifications,
        speaker,
        effects,
        announcementGate,
        chimeController,
    ) {
        WeatherWarningController(
            context = context.applicationContext,
            earthquakeSettings = earthquakeSettings,
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
