package app.smartdisplay.away

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.audio.EffectPlayer
import app.smartdisplay.audio.MusicVolumeBoost
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.notification.NotificationCategoryAwayAlert
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import app.smartdisplay.switchbot.SwitchBotBulbStatus
import app.smartdisplay.switchbot.SwitchBotClient
import app.smartdisplay.switchbot.switchBotConfigured
import app.smartdisplay.switchbot.switchBotSecret
import app.smartdisplay.switchbot.switchBotToken
import app.smartdisplay.util.InternetConnectivity
import app.smartdisplay.util.showOsAlert
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AwayPinResult {
    Accepted,
    Wrong,
    Locked,
}

/**
 * キッチンライトが薄い黄色（255:255:183）・明るさ15%のときだけ外出アラートにする。
 * 白色は色温度つきで点くので、点いているだけでは発報しない。
 */
class AwayAlertController(
    context: Context,
    private val settings: AwayAlertSettings,
    private val notifications: NotificationCenter,
    private val speaker: SpeechSpeaker,
    private val effects: EffectPlayer,
    private val announcementGate: AnnouncementGate,
    private val chimeController: HourlyChimeController,
    private val atHome: () -> Boolean,
    private val yieldToHigherCrisis: () -> Boolean,
) {
    private val appContext = context.applicationContext
    val archive = AwayAlertArchive(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private val client = SwitchBotClient()
    private val music = AwayAlertMusic(appContext)
    private var monitorJob: Job? = null
    private var alertJob: Job? = null
    private var captureJob: Job? = null
    private var currentAlertId: String? = null
    private var deviceId: String? = null
    private var suppressUntilElapsed = 0L
    private var sessionOpen = false
    private var pinFailures = 0
    private var menuPinFailures = 0

    private val _menuLockoutUntilElapsed = MutableStateFlow(0L)
    val menuLockoutUntilElapsed: StateFlow<Long> = _menuLockoutUntilElapsed.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _forceCloseMenu = MutableStateFlow(0)
    val forceCloseMenu: StateFlow<Int> = _forceCloseMenu.asStateFlow()

    private val _lockoutUntilElapsed = MutableStateFlow(0L)
    val lockoutUntilElapsed: StateFlow<Long> = _lockoutUntilElapsed.asStateFlow()

    private val _unlocking = MutableStateFlow(false)
    val unlocking: StateFlow<Boolean> = _unlocking.asStateFlow()

    fun start() {
        if (monitorJob?.isActive == true) {
            return
        }
        archive.closeOpenRecords()
        monitorJob = scope.launch {
            while (isActive) {
                val started = SystemClock.elapsedRealtime()
                runCatching { pollOnce() }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            throw error
                        }
                    }
                val remain = PollIntervalMs - (SystemClock.elapsedRealtime() - started)
                if (remain > 0) {
                    delay(remain)
                }
            }
        }
    }

    fun release() {
        _active.value = false
        speaker.stop()
        music.stop()
        currentAlertId?.let { archive.finish(it) }
        currentAlertId = null
        if (sessionOpen) {
            sessionOpen = false
            notifications.setAwayAlertExclusive(false)
            chimeController.holdAwayAlert(false)
        }
        scope.cancel()
    }

    fun submitPin(code: String): AwayPinResult {
        if (!_active.value || _unlocking.value) {
            return AwayPinResult.Accepted
        }
        val now = SystemClock.elapsedRealtime()
        if (now < _lockoutUntilElapsed.value) {
            return AwayPinResult.Locked
        }
        if (code != PinCode) {
            pinFailures += 1
            if (pinFailures >= MaxPinFailures) {
                pinFailures = 0
                _lockoutUntilElapsed.value = now + LockoutMs
                return AwayPinResult.Locked
            }
            return AwayPinResult.Wrong
        }
        pinFailures = 0
        _lockoutUntilElapsed.value = 0L
        _unlocking.value = true
        scope.launch { dismissFromApp() }
        return AwayPinResult.Accepted
    }

    fun submitMenuPin(code: String): AwayPinResult {
        val now = SystemClock.elapsedRealtime()
        if (now < _menuLockoutUntilElapsed.value) {
            return AwayPinResult.Locked
        }
        if (code != PinCode) {
            menuPinFailures += 1
            if (menuPinFailures >= MaxPinFailures) {
                menuPinFailures = 0
                _menuLockoutUntilElapsed.value = now + LockoutMs
                captureMenuFailure()
                return AwayPinResult.Locked
            }
            return AwayPinResult.Wrong
        }
        menuPinFailures = 0
        _menuLockoutUntilElapsed.value = 0L
        return AwayPinResult.Accepted
    }

    private fun captureMenuFailure() {
        scope.launch {
            val alertId = archive.beginFailedUnlock()
            val camera = FrontCameraCapture(appContext)
            try {
                runInterruptible(Dispatchers.IO) {
                    captureFrame(camera, alertId)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    camera.close()
                }
            }
        }
    }

    private suspend fun pollOnce() {
        if (!switchBotConfigured || !InternetConnectivity.hasInternet()) {
            return
        }
        val alerting = _active.value
        if (!alerting && atHome()) {
            return
        }
        val status = fetchKitchen() ?: return
        val lit = status.isKitchenAwayAlert()
        if (alerting) {
            if (!lit) {
                finishAlert()
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < suppressUntilElapsed) {
            if (!lit) {
                suppressUntilElapsed = 0L
            }
            return
        }
        if (lit) {
            beginAlert()
        }
    }

    private suspend fun fetchKitchen(): SwitchBotBulbStatus? {
        val id = deviceId ?: resolveDeviceId() ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.fetchBulbStatus(switchBotToken, switchBotSecret, id)
            }.getOrElse { error ->
                if (error is CancellationException) {
                    throw error
                }
                null
            }
        }
    }

    private suspend fun resolveDeviceId(): String? {
        val id = withContext(Dispatchers.IO) {
            runCatching {
                client.fetchInventory(switchBotToken, switchBotSecret)
                    .deviceIds[KitchenLight]
            }.getOrElse { error ->
                if (error is CancellationException) {
                    throw error
                }
                null
            }
        }
        if (!id.isNullOrBlank()) {
            deviceId = id
        }
        return deviceId
    }

    private fun beginAlert() {
        if (_active.value || _unlocking.value) {
            return
        }
        scope.launch {
            sessionMutex.withLock {
                if (_active.value || _unlocking.value) {
                    return@withLock
                }
                _active.value = true
                sessionOpen = true
                _forceCloseMenu.value = _forceCloseMenu.value + 1
                notifications.abortForEarthquakePriority()
                notifications.setAwayAlertExclusive(true)
                notifications.post(
                    category = NotificationCategoryAwayAlert,
                    title = "アラート",
                    body = "外出モードのアラートが発動しました",
                    speechText = "",
                    alert = false,
                    soundEffect = NotificationSoundEffect.None,
                    skipSpeech = true,
                    retained = true,
                )
                chimeController.holdAwayAlert(true)
                val alertId = archive.begin()
                currentAlertId = alertId
                alertJob = scope.launch { runAlertAudio() }
                captureJob = scope.launch { runCapture(alertId) }
            }
        }
    }

    private suspend fun finishAlert() {
        sessionMutex.withLock {
            val job = alertJob
            val capture = captureJob
            if (!sessionOpen && job == null && capture == null) {
                return
            }
            sessionOpen = false
            _active.value = false
            alertJob = null
            captureJob = null
            currentAlertId?.let { archive.finish(it) }
            currentAlertId = null
            notifications.setAwayAlertExclusive(false)
            chimeController.holdAwayAlert(false)
            speaker.stop()
            music.stop()
            capture?.cancel()
            job?.cancel()
            job?.join()
        }
    }

    private suspend fun runCapture(alertId: String) {
        val camera = FrontCameraCapture(appContext)
        try {
            while (_active.value) {
                val started = SystemClock.elapsedRealtime()
                runInterruptible(Dispatchers.IO) {
                    captureFrame(camera, alertId)
                }
                val remain = CaptureIntervalMs - (SystemClock.elapsedRealtime() - started)
                if (remain > 0) {
                    delay(remain)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                camera.close()
            }
        }
    }

    private fun captureFrame(camera: FrontCameraCapture, alertId: String): Boolean {
        if (Thread.currentThread().isInterrupted) {
            return false
        }
        if (!camera.isOpen() && !camera.open()) {
            return false
        }
        archive.ensureSpace()
        if (!archive.hasRoomForPhoto() || !archive.contains(alertId)) {
            return false
        }
        val file = archive.newPhotoFile(alertId)
        val saved = camera.capture(file)
        if (!saved) {
            file.delete()
            return false
        }
        archive.bump()
        return true
    }

    private suspend fun runAlertAudio() {
        MusicVolumeBoost.raise(appContext, settings.volumeRatio)
        try {
            if (_active.value) {
                music.start()
                announceLoop()
            }
        } finally {
            music.stop()
            MusicVolumeBoost.restore(appContext)
        }
    }

    private suspend fun announceLoop() {
        while (_active.value) {
            if (yieldToHigherCrisis()) {
                music.pause()
                delay(YieldStepMs)
                continue
            }
            music.resume()
            val started = SystemClock.elapsedRealtime()
            music.setDucked(true)
            try {
                announcementGate.withLock {
                    if (!_active.value || yieldToHigherCrisis()) {
                        return@withLock
                    }
                    runCatching { effects.playAlert1() }
                        .onFailure { error ->
                            if (error is CancellationException) {
                                throw error
                            }
                        }
                    if (!_active.value || yieldToHigherCrisis()) {
                        return@withLock
                    }
                    speaker.speakNow(SpeechText)
                }
            } finally {
                music.setDucked(false)
                if (yieldToHigherCrisis()) {
                    music.pause()
                }
            }
            var left = AnnounceIntervalMs - (SystemClock.elapsedRealtime() - started)
            while (_active.value && left > 0) {
                if (yieldToHigherCrisis()) {
                    music.pause()
                } else {
                    music.resume()
                }
                val slice = minOf(YieldStepMs, left)
                delay(slice)
                left -= slice
            }
        }
    }

    private suspend fun dismissFromApp() {
        try {
            val outcome = withContext(Dispatchers.IO) {
                runDismissCommands()
            }
            if (!outcome.lightOff && !outcome.sceneOk) {
                showOsAlert(outcome.message ?: "解除できませんでした")
                return
            }
            if (!outcome.lightOff || !outcome.sceneOk) {
                showOsAlert(outcome.message ?: "解除の一部に失敗しました")
            }
            suppressUntilElapsed = SystemClock.elapsedRealtime() + SuppressAfterDismissMs
            finishAlert()
        } finally {
            _unlocking.value = false
        }
    }

    private fun runDismissCommands(): DismissOutcome {
        val index = runCatching {
            client.fetchInventory(switchBotToken, switchBotSecret)
        }.getOrElse { error ->
            if (error is CancellationException) {
                throw error
            }
            return DismissOutcome(false, false, "解除できませんでした")
        }
        val lightId = index.deviceIds[KitchenLight]
        if (!lightId.isNullOrBlank()) {
            deviceId = lightId
        }
        val lightOff = if (lightId.isNullOrBlank()) {
            false
        } else {
            runCatching {
                client.sendCommand(
                    token = switchBotToken,
                    secret = switchBotSecret,
                    deviceId = lightId,
                    command = "turnOff",
                    parameter = "default",
                    commandType = "command",
                )
            }.isSuccess
        }
        val sceneId = index.sceneIds[HomeScene]
        val sceneOk = if (sceneId.isNullOrBlank()) {
            false
        } else {
            runCatching {
                client.executeScene(switchBotToken, switchBotSecret, sceneId)
            }.isSuccess
        }
        val message = when {
            lightOff && sceneOk -> null
            !lightOff && !sceneOk -> "解除できませんでした"
            !lightOff -> "キッチンライトを消せませんでした"
            else -> "在宅モードを実行できませんでした"
        }
        return DismissOutcome(lightOff, sceneOk, message)
    }

    private data class DismissOutcome(
        val lightOff: Boolean,
        val sceneOk: Boolean,
        val message: String?,
    )

    companion object {
        private const val KitchenLight = "キッチンライト"
        private const val HomeScene = "在宅モード"
        private const val PinCode = "2934"
        private const val PollIntervalMs = 12_000L
        private const val CaptureIntervalMs = 10_000L
        private const val AnnounceIntervalMs = 90_000L
        private const val YieldStepMs = 400L
        private const val LockoutMs = 120_000L
        private const val MaxPinFailures = 3
        private const val SuppressAfterDismissMs = 25_000L
        private const val SpeechText = "アラートが発動しました。アラートが発動しました。"
    }
}

private fun SwitchBotBulbStatus.isKitchenAwayAlert(): Boolean {
    return powerOn &&
        brightness == 15 &&
        colorTemperature == 0 &&
        red == 255 &&
        green == 255 &&
        blue == 183
}

@Composable
fun rememberAwayAlertController(
    settings: AwayAlertSettings,
    notifications: NotificationCenter,
    speaker: SpeechSpeaker,
    effects: EffectPlayer,
    announcementGate: AnnouncementGate,
    chimeController: HourlyChimeController,
    atHome: () -> Boolean,
    yieldToHigherCrisis: () -> Boolean,
): AwayAlertController {
    val context = LocalContext.current
    val atHomeState = rememberUpdatedState(atHome)
    val yieldState = rememberUpdatedState(yieldToHigherCrisis)
    val controller = remember(
        settings,
        notifications,
        speaker,
        effects,
        announcementGate,
        chimeController,
    ) {
        AwayAlertController(
            context = context.applicationContext,
            settings = settings,
            notifications = notifications,
            speaker = speaker,
            effects = effects,
            announcementGate = announcementGate,
            chimeController = chimeController,
            atHome = { atHomeState.value.invoke() },
            yieldToHigherCrisis = { yieldState.value.invoke() },
        )
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.release() }
    }
    return controller
}
