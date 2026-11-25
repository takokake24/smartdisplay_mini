package app.smartdisplay.speech

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 時報・通知など、音声案内を一件ずつ順番に出すための共通ロック。
 * 読み上げ中かどうかは [speechActive] で参照でき、時報メロディのダッキングに使う。
 * 時報の時刻読み上げは [withPriorityLock] で、進行中の通知読み上げを切り上げて割り込める。
 * 時報一連（メロディ〜時刻読み上げ）中は [chimeSequenceActive] が true になる。
 */
class AnnouncementGate(
    private val onAudibleChanged: (Boolean) -> Unit = {},
) {
    private val mutex = Mutex()
    private val _speechActive = MutableStateFlow(false)
    val speechActive: StateFlow<Boolean> = _speechActive.asStateFlow()

    private val _chimeSequenceActive = MutableStateFlow(false)
    val chimeSequenceActive: StateFlow<Boolean> = _chimeSequenceActive.asStateFlow()
    private var audible = false

    @Volatile
    private var priorityTicket: CompletableDeferred<Unit>? = null

    fun setChimeSequenceActive(active: Boolean) {
        _chimeSequenceActive.value = active
        dispatchAudible()
    }

    /** 時報の一連の流れが終わるまで待つ。 */
    suspend fun awaitChimeSequenceIdle() {
        _chimeSequenceActive.first { !it }
    }

    /**
     * 時報が鳴り始めそうなら、一連が終わるまで待つ。
     * まだ始まっていなければ [startWaitMs] だけ開始を待ち、始まらなければ戻る。
     */
    suspend fun awaitChimeIfStarting(startWaitMs: Long = 2_000L) {
        if (!_chimeSequenceActive.value) {
            withTimeoutOrNull(startWaitMs) {
                _chimeSequenceActive.first { it }
            }
        }
        awaitChimeSequenceIdle()
    }

    /**
     * 通常の読み上げ。時報の割り込み待ちがあれば、それが終わるまで開始しない。
     */
    suspend fun <T> withLock(block: suspend () -> T): T {
        while (true) {
            priorityTicket?.await()
            val outcome = mutex.withLock {
                val ticket = priorityTicket
                if (ticket != null && !ticket.isCompleted) {
                    null
                } else {
                    _speechActive.value = true
                    dispatchAudible()
                    try {
                        Result.success(block())
                    } finally {
                        _speechActive.value = false
                        dispatchAudible()
                    }
                }
            }
            if (outcome != null) {
                return outcome.getOrThrow()
            }
            priorityTicket?.await()
        }
    }

    /**
     * 時報など優先読み上げ。
     * 1. 割り込みチケットを出す（通常枠は開始待ちになる）
     * 2. [interrupt] で進行中の TTS を止める
     * 3. ロック取得後に [block] を実行する
     */
    suspend fun <T> withPriorityLock(
        interrupt: () -> Unit = {},
        block: suspend () -> T,
    ): T {
        val ticket = CompletableDeferred<Unit>()
        priorityTicket = ticket
        try {
            interrupt()
            return mutex.withLock {
                _speechActive.value = true
                dispatchAudible()
                try {
                    block()
                } finally {
                    _speechActive.value = false
                    dispatchAudible()
                }
            }
        } finally {
            ticket.complete(Unit)
            if (priorityTicket === ticket) {
                priorityTicket = null
            }
        }
    }

    /** 時報の一連、または通知などの読み上げが鳴っているあいだだけ true。 */
    private fun dispatchAudible() {
        val next = _chimeSequenceActive.value || _speechActive.value
        if (next == audible) {
            return
        }
        audible = next
        onAudibleChanged(next)
    }

    /** 時報割り込み待ち中なら、この先の通知読み上げを始めない。 */
    fun shouldContinueSpeech(): Boolean {
        val ticket = priorityTicket
        return ticket == null || ticket.isCompleted
    }
}

@Composable
fun rememberAnnouncementGate(
    onAudibleChanged: (Boolean) -> Unit = {},
): AnnouncementGate {
    val current by rememberUpdatedState(onAudibleChanged)
    return remember {
        AnnouncementGate { active -> current(active) }
    }
}
