package app.smartdisplay.notification

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.audio.EffectPlayer
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
 * 画面右半分の通知バナーと履歴をまとめて扱う。
 * 音声案内は [AnnouncementGate] 経由で、時報など他の案内と順番に出す。
 * 複数同時に来た場合は読み上げ完了ごとに次へ遷移し、前のものは未確認のまま履歴に残す。
 */
class NotificationCenter(
    private val speaker: SpeechSpeaker,
    private val effects: EffectPlayer,
    private val announcementGate: AnnouncementGate,
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

    private val _history = MutableStateFlow<List<AppNotification>>(emptyList())
    val history: StateFlow<List<AppNotification>> = _history.asStateFlow()

    private var pruneJob: Job? = null

    init {
        pruneJob = scope.launch {
            while (isActive) {
                pruneExpiredHistory()
                delay(NotificationHistoryPruneIntervalMillis)
            }
        }
    }

    fun post(
        category: String,
        title: String,
        body: String,
        speechText: String,
        alert: Boolean = true,
        addToHistory: Boolean = true,
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
            read = false,
        )
        pruneExpiredHistory()
        if (addToHistory) {
            _history.update { listOf(item) + it }
        }
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
        val activeId = _presentation.value?.notification?.id
        stopAlertPlayback()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        if (activeId != null) {
            _history.update { list -> list.filterNot { it.id == activeId } }
        }
        ensurePresenting()
    }

    private fun dismissActive() {
        stopAlertPlayback()
        pendingAlerts.clear()
        autoDismissJob?.cancel()
        autoDismissJob = null
        _presentation.value = null
        resetQueueProgress()
    }

    fun markRead(id: String) {
        _history.update { list ->
            list.map { item ->
                if (item.id == id) item.copy(read = true) else item
            }
        }
    }

    fun remove(id: String) {
        pendingAlerts.removeAll { it.id == id }
        deferredAlerts.removeAll { it.id == id }
        _history.update { list -> list.filterNot { it.id == id } }
        if (_presentation.value?.notification?.id == id) {
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            ensurePresenting()
        } else {
            expandBatchTotalForPending()
            refreshPresentationProgress()
        }
    }

    fun clearHistory() {
        pendingAlerts.clear()
        deferredAlerts.clear()
        _history.value = emptyList()
        dismissActive()
    }

    fun release() {
        stopAlertPlayback()
        pendingAlerts.clear()
        autoDismissJob?.cancel()
        pruneJob?.cancel()
        pruneJob = null
        scope.cancel()
    }

    private fun pruneExpiredHistory() {
        val cutoff = LocalDateTime.now().minusHours(NotificationHistoryRetentionHours)
        val expiredIds = _history.value
            .asSequence()
            .filter { it.createdAt.isBefore(cutoff) }
            .map { it.id }
            .toSet()
        if (expiredIds.isEmpty()) {
            return
        }
        pendingAlerts.removeAll { it.id in expiredIds }
        deferredAlerts.removeAll { it.id in expiredIds }
        _history.update { list -> list.filterNot { it.id in expiredIds } }
        val activeId = _presentation.value?.notification?.id
        if (activeId != null && activeId in expiredIds) {
            stopAlertPlayback()
            autoDismissJob?.cancel()
            autoDismissJob = null
            _presentation.value = null
            ensurePresenting()
        } else {
            expandBatchTotalForPending()
            refreshPresentationProgress()
        }
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
                    if (speak || current.soundEffect != NotificationSoundEffect.None) {
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
): NotificationCenter {
    val center = remember(speaker, effects, announcementGate) {
        NotificationCenter(
            speaker = speaker,
            effects = effects,
            announcementGate = announcementGate,
        )
    }
    DisposableEffect(center) {
        onDispose { center.release() }
    }
    return center
}
