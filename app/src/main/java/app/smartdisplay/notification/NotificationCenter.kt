package app.smartdisplay.notification

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.audio.EffectPlayer
import app.smartdisplay.audio.SoundPresenceGate
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import app.smartdisplay.speech.breathAtHalfWidthSpace
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
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.math.max

data class ActiveNotificationPresentation(
    val notification: AppNotification,
    val queueProgress: NotificationQueueProgress?,
)

/**
 * 画面右半分の通知バナーを扱う。
 * 音声案内は [AnnouncementGate] 経由で、時報など他の案内と順番に出す。
 * 複数同時に来た場合は読み上げ完了ごとに次へ遷移する。
 */
class NotificationCenter(
    private val speaker: SpeechSpeaker,
    private val effects: EffectPlayer,
    private val announcementGate: AnnouncementGate,
    private val presenceGate: SoundPresenceGate? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingAlerts = ArrayDeque<AppNotification>()
    private val deferredAlerts = ArrayDeque<AppNotification>()
    private var autoDismissJob: Job? = null
    private var presentingJob: Job? = null
    private var batchPresented = 0
    private var batchTotal = 0

    @Volatile
    private var soundTestHold = false

    private val _presentation = MutableStateFlow<ActiveNotificationPresentation?>(null)
    val presentation: StateFlow<ActiveNotificationPresentation?> = _presentation.asStateFlow()

    fun post(
        category: String,
        title: String,
        body: String,
        speechText: String,
        alert: Boolean = true,
        soundEffect: NotificationSoundEffect = NotificationSoundEffect.Notice,
        skipSpeech: Boolean = false,
        appIcon: Bitmap? = null,
        sourceLabel: String = "",
        speakNotBeforeElapsed: Long = 0L,
    ): String {
        val item = AppNotification(
            id = UUID.randomUUID().toString(),
            category = category,
            title = title,
            body = body,
            speechText = speechText,
            createdAt = LocalDateTime.now(),
            soundEffect = soundEffect,
            skipSpeech = skipSpeech,
            appIcon = appIcon,
            sourceLabel = sourceLabel,
            speakNotBeforeElapsed = speakNotBeforeElapsed,
        )
        if (!alert) {
            return item.id
        }
        if (soundTestHold) {
            deferredAlerts.addLast(item)
            return item.id
        }
        pendingAlerts.addLast(item)
        expandBatchTotalForPending()
        refreshPresentationProgress()
        ensurePresenting()
        return item.id
    }

    /**
     * サウンドテスト中は通知を保留する。抜けたら保留分を表示する。
     */
    fun setSoundTestHold(active: Boolean) {
        val was = soundTestHold
        soundTestHold = active
        if (active) {
            shelveForSoundTest()
            return
        }
        if (was) {
            flushDeferredAlerts()
        }
    }

    private fun shelveForSoundTest() {
        val active = _presentation.value?.notification
        if (active != null) {
            deferredAlerts.addFirst(active)
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            resetQueueProgress()
        }
        deferredAlerts.addAll(pendingAlerts)
        pendingAlerts.clear()
    }

    private fun flushDeferredAlerts() {
        pendingAlerts.addAll(deferredAlerts)
        deferredAlerts.clear()
        expandBatchTotalForPending()
        refreshPresentationProgress()
        ensurePresenting()
    }

    fun dismissActiveManually() {
        stopAlertPlayback()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        ensurePresenting()
    }

    fun release() {
        stopAlertPlayback()
        pendingAlerts.clear()
        deferredAlerts.clear()
        autoDismissJob?.cancel()
        scope.cancel()
    }

    private fun ensurePresenting() {
        if (presentingJob?.isActive == true) {
            return
        }
        if (pendingAlerts.isEmpty()) {
            return
        }
        if (_presentation.value == null) {
            batchPresented = 0
            batchTotal = pendingAlerts.size
        }
        presentingJob = scope.launch {
            try {
                while (true) {
                    val current = pendingAlerts.removeFirstOrNull() ?: break
                    batchPresented += 1
                    batchTotal = max(batchTotal, batchPresented + pendingAlerts.size)
                    showBanner(current)
                    // 時報鳴動中に来た通知は表示だけ先に出し、読み上げは時報一連の後
                    announcementGate.awaitChimeSequenceIdle()
                    // 端末側の通知音が鳴り終わるまで読み上げを始めない
                    awaitExternalSound(current)
                    announcementGate.awaitChimeSequenceIdle()
                    // 表示の1分タイムアウトは、読み上げ処理の開始時点から数える
                    startAutoDismiss(current.id)
                    val speak = !current.skipSpeech && current.speechText.isNotBlank()
                    val audible = presenceGate?.mayPlayNow() != false
                    if (audible && (speak || current.soundEffect != NotificationSoundEffect.None)) {
                        announcementGate.withLock {
                            if (!announcementGate.shouldContinueSpeech()) {
                                return@withLock
                            }
                            when (current.soundEffect) {
                                NotificationSoundEffect.None -> Unit
                                NotificationSoundEffect.Notice -> {
                                    runCatching { effects.playNotice() }
                                }
                                NotificationSoundEffect.Beep -> {
                                    runCatching { effects.playBeepAndAwait() }
                                }
                            }
                            if (!speak || !announcementGate.shouldContinueSpeech()) {
                                return@withLock
                            }
                            speaker.speakNow(spokenText(current))
                        }
                    }
                    // 読み上げ中に時報が始まった場合も、一連が終わるまで次へ進まない
                    announcementGate.awaitChimeSequenceIdle()
                }
            } catch (_: CancellationException) {
                // 手動閉じ・停止時
            } finally {
                if (presentingJob === this) {
                    presentingJob = null
                }
            }
        }
    }

    private suspend fun awaitExternalSound(item: AppNotification) {
        val remaining = item.speakNotBeforeElapsed - SystemClock.elapsedRealtime()
        if (remaining > 0L) {
            delay(remaining)
        }
    }

    private fun stopAlertPlayback() {
        presentingJob?.cancel()
        presentingJob = null
        speaker.stop()
    }

    private fun showBanner(item: AppNotification) {
        val progress = if (batchTotal > 1) {
            NotificationQueueProgress(current = batchPresented, total = batchTotal)
        } else {
            null
        }
        _presentation.value = ActiveNotificationPresentation(
            notification = item,
            queueProgress = progress,
        )
        // タイムアウトは読み上げ開始時に startAutoDismiss する
        autoDismissJob?.cancel()
        autoDismissJob = null
    }

    private fun startAutoDismiss(notificationId: String) {
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(ActiveNotificationTimeoutMillis)
            if (_presentation.value?.notification?.id == notificationId) {
                _presentation.value = null
                if (pendingAlerts.isEmpty()) {
                    resetQueueProgress()
                }
            }
        }
    }

    private fun expandBatchTotalForPending() {
        val shown = if (_presentation.value != null) batchPresented else 0
        batchTotal = max(batchTotal, shown + pendingAlerts.size)
    }

    private fun refreshPresentationProgress() {
        val current = _presentation.value ?: return
        val progress = if (batchTotal > 1) {
            NotificationQueueProgress(
                current = batchPresented.coerceAtLeast(1),
                total = batchTotal,
            )
        } else {
            null
        }
        if (current.queueProgress != progress) {
            _presentation.value = current.copy(queueProgress = progress)
        }
    }

    private fun spokenText(item: AppNotification): String {
        val text = item.speechText
        if (item.category != NotificationCategoryDevice) {
            return text
        }
        return breathAtHalfWidthSpace(text)
    }

    private fun resetQueueProgress() {
        batchPresented = 0
        batchTotal = 0
    }
}

@Composable
fun rememberNotificationCenter(
    speaker: SpeechSpeaker,
    effects: EffectPlayer,
    announcementGate: AnnouncementGate,
    presenceGate: SoundPresenceGate? = null,
): NotificationCenter {
    val center = remember(speaker, effects, announcementGate, presenceGate) {
        NotificationCenter(
            speaker = speaker,
            effects = effects,
            announcementGate = announcementGate,
            presenceGate = presenceGate,
        )
    }
    DisposableEffect(center) {
        onDispose { center.release() }
    }
    return center
}
