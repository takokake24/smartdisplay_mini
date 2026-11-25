package app.smartdisplay.jihou

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import app.smartdisplay.R
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import app.smartdisplay.speech.breathAtHalfWidthSpace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HourlyChimeController(
    context: Context,
    private val speaker: SpeechSpeaker,
    private val announcementGate: AnnouncementGate,
    private val seasonFor: (LocalDateTime) -> JihouSeason = { JihouSeason.Normal },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val playbackScale: (LocalDateTime) -> Float = { _ -> 1f },
    initiallyEnabled: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackMutex = Mutex()
    private val durationCache = mutableMapOf<Int, Long>()
    private var loopJob: Job? = null
    private var testJob: Job? = null
    private var duckJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var queuedPlayer: MediaPlayer? = null
    private var lastAnnouncedKey: String? = null
    private var enabled: Boolean = initiallyEnabled
    @Volatile
    private var testPlayback = false
    private var chimeAt: LocalDateTime? = null
    private var suspendedByExternalInterrupt: Boolean = false
    private var legacyInterrupt: Boolean = false
    private var awayAlertHold: Boolean = false
    private var soundTestHold: Boolean = false
    private var currentPlaybackDone: CompletableDeferred<Unit>? = null
    private var onChimeStarted: ((LocalDateTime) -> Unit)? = null
    private var onTimeAnnounced: ((LocalDateTime) -> Unit)? = null
    private var onAfterTimeAnnounce: (suspend (LocalDateTime) -> Unit)? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewStopJob: Job? = null

    init {
        duckJob = scope.launch {
            announcementGate.speechActive.collect { speaking ->
                playbackMutex.withLock {
                    applyPlaybackVolumeLocked(ducked = speaking)
                }
            }
        }
    }

    /** 時報の音が鳴り始めたとき。メロディを省略して時刻だけ読むときも、読み上げの前に呼ぶ。 */
    fun setOnChimeStarted(listener: ((LocalDateTime) -> Unit)?) {
        onChimeStarted = listener
    }

    fun setOnAfterTimeAnnounce(listener: (suspend (LocalDateTime) -> Unit)?) {
        onAfterTimeAnnounce = listener
    }

    /** 時刻の読み上げが終わったとき。続く案内の前。 */
    fun setOnTimeAnnounced(listener: ((LocalDateTime) -> Unit)?) {
        onTimeAnnounced = listener
    }

    private fun notifyChimeStarted(time: LocalDateTime) {
        runCatching { onChimeStarted?.invoke(time) }
    }

    private fun notifyTimeAnnounced(time: LocalDateTime) {
        runCatching { onTimeAnnounced?.invoke(time) }
    }

    /** ナイトモードの時報音量を、通常の時報と同じ割合で短く鳴らす。 */
    fun previewChimeVolume(scale: Float) {
        val volume = NormalVolume * scale.coerceIn(0f, 1f)
        previewStopJob?.cancel()
        previewStopJob = scope.launch {
            val player = MediaPlayer.create(appContext, R.raw.jihou_normal_1) ?: return@launch
            player.setAudioAttributes(chimeAudioAttributes())
            player.setVolume(volume, volume)
            player.setOnCompletionListener {
                scope.launch {
                    if (previewPlayer === player) {
                        previewPlayer = null
                        releaseOne(player)
                    }
                }
            }
            stopVolumePreviewPlayer()
            previewPlayer = player
            if (!runCatching { player.start() }.isSuccess) {
                previewPlayer = null
                releaseOne(player)
                return@launch
            }
            try {
                delay(PreviewChimeMs)
            } finally {
                if (previewPlayer === player) {
                    stopVolumePreviewPlayer()
                }
            }
        }
    }

    fun stopVolumePreview() {
        previewStopJob?.cancel()
        previewStopJob = null
        stopVolumePreviewPlayer()
    }

    private fun stopVolumePreviewPlayer() {
        val player = previewPlayer ?: return
        previewPlayer = null
        releaseOne(player)
    }

    /**
     * 緊急地震速報など最優先の割り込み用。
     * メロディと時報ループを止め、表示解除後に [resumeAfterInterrupt] で再開する。
     */
    fun interruptPlayback() {
        legacyInterrupt = true
        syncExternalInterrupt()
        stopExternalPlayback()
    }

    /**
     * 外出アラートのあいだ時報を止める。
     * 緊急地震速報の [resumeAfterInterrupt] では解けない。
     */
    fun holdAwayAlert(held: Boolean) {
        val changed = awayAlertHold != held
        awayAlertHold = held
        syncExternalInterrupt()
        if (held) {
            stopExternalPlayback()
            return
        }
        if (changed && !suspendedByExternalInterrupt) {
            start()
        }
    }

    /** サウンドテストのあいだ時報を止める。抜けたらスケジュールを再開する。 */
    fun holdSoundTest(held: Boolean) {
        val changed = soundTestHold != held
        soundTestHold = held
        syncExternalInterrupt()
        if (held) {
            stopExternalPlayback()
            return
        }
        if (changed && !suspendedByExternalInterrupt) {
            start()
        }
    }

    /** [interruptPlayback] 後に時報スケジュールを再開する。外出アラート中は再開しない。 */
    fun resumeAfterInterrupt() {
        legacyInterrupt = false
        syncExternalInterrupt()
        if (!suspendedByExternalInterrupt && enabled && loopJob?.isActive != true && testJob?.isActive != true) {
            start()
        }
    }

    private fun syncExternalInterrupt() {
        suspendedByExternalInterrupt = legacyInterrupt || awayAlertHold || soundTestHold
    }

    private fun stopExternalPlayback() {
        scope.launch {
            playbackMutex.withLock {
                currentPlaybackDone?.complete(Unit)
                currentPlaybackDone = null
                releasePlayerLocked()
            }
            testJob?.cancel()
            testJob = null
            loopJob?.cancel()
            loopJob = null
            announcementGate.setChimeSequenceActive(false)
        }
    }

    fun start() {
        if (!enabled || suspendedByExternalInterrupt || loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launch {
            while (isActive) {
                if (!enabled || suspendedByExternalInterrupt) {
                    delay(1_000L)
                    continue
                }
                runCatching { awaitAndPlayNext() }
                    .onFailure { delay(1_000L) }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) {
            return
        }
        enabled = value
        if (value) {
            if (testJob?.isActive != true) {
                start()
            }
        } else {
            loopJob?.cancel()
            loopJob = null
            if (testJob?.isActive != true) {
                releasePlayerSync()
            }
        }
    }

    private fun currentSeason(at: LocalDateTime = LocalDateTime.now(zoneId)): JihouSeason {
        return seasonFor(at)
    }

    /** 四半期メロディをランダムに1本鳴らしてから、現在時刻を読み上げる。 */
    fun playTest(testSeason: JihouSeason = currentSeason()) {
        playTestRandom(testSeason)
    }

    fun playTestRandom(testSeason: JihouSeason = currentSeason()) {
        runTestSequence(testSeason) { selected ->
            val resId = selected.soundResId((1..4).random())
            awaitPlayFull(resId)
            speakTestAnnouncement(LocalDateTime.now(zoneId))
        }
    }

    /** 0 / 15 / 30 / 45 分のいずれかのメロディを鳴らして読み上げる。 */
    fun playTestQuarter(minute: Int, testSeason: JihouSeason = currentSeason()) {
        val quarter = quarterIndexForMinute(minute) ?: return
        runTestSequence(testSeason) { selected ->
            awaitPlayFull(selected.soundResId(quarter))
            val announceAt = LocalDateTime.now(zoneId)
                .withMinute(minute)
                .withSecond(0)
                .withNano(0)
            speakTestAnnouncement(announceAt)
        }
    }

    /**
     * 特殊時刻（イントロ付き）のテスト。
     * イントロ → 該当四半期メロディ → 時刻読み上げの順。
     */
    fun playTestSpecial(
        hour: Int,
        minute: Int = 0,
        testSeason: JihouSeason = currentSeason(),
    ) {
        val introResId = testSeason.introSoundResId(hour, minute) ?: return
        val quarter = quarterIndexForMinute(minute) ?: return
        runTestSequence(testSeason) { selected ->
            awaitPlayGapless(introResId, selected.soundResId(quarter))
            val announceAt = LocalDateTime.now(zoneId)
                .withHour(hour)
                .withMinute(minute)
                .withSecond(0)
                .withNano(0)
            speakTestAnnouncement(announceAt)
        }
    }

    /**
     * 指定時刻の時報（イントロがあればそのあとメロディ）を最後まで鳴らし、時刻を読み上げてから戻る。
     * 正時は分を読まない。3の倍数時は日付・曜日・行事のあとに時刻を読む。
     */
    suspend fun playScheduledChime(hour: Int, minute: Int) {
        val quarter = quarterIndexForMinute(minute) ?: return
        if (testJob?.isActive == true) {
            return
        }
        val shouldResume = enabled
        loopJob?.cancel()
        loopJob = null
        val job = currentCoroutineContext()[Job]
        testJob = job
        announcementGate.setChimeSequenceActive(true)
        chimeAt = LocalDateTime.now(zoneId)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
        try {
            val selected = currentSeason()
            val introResId = selected.introSoundResId(hour, minute)
            if (introResId != null) {
                awaitPlayGapless(introResId, selected.soundResId(quarter))
            } else {
                awaitPlayFull(selected.soundResId(quarter))
            }
            val announceAt = LocalDateTime.now(zoneId)
                .withHour(hour)
                .withMinute(minute)
                .withSecond(0)
                .withNano(0)
            speakTestAnnouncement(announceAt)
        } finally {
            chimeAt = null
            if (testJob === job) {
                testJob = null
            }
            announcementGate.setChimeSequenceActive(false)
            if (shouldResume) {
                start()
            }
        }
    }

    private fun runTestSequence(
        testSeason: JihouSeason,
        block: suspend (JihouSeason) -> Unit,
    ) {
        if (testJob?.isActive == true) {
            return
        }
        testJob = scope.launch {
            val shouldResume = enabled
            loopJob?.cancel()
            loopJob = null
            testPlayback = true
            announcementGate.setChimeSequenceActive(true)
            try {
                block(testSeason)
            } finally {
                testPlayback = false
                announcementGate.setChimeSequenceActive(false)
                testJob = null
                if (shouldResume) {
                    start()
                }
            }
        }
    }

    private suspend fun speakTestAnnouncement(time: LocalDateTime) {
        announcementGate.withPriorityLock(
            interrupt = { speaker.stop() },
        ) {
            speaker.speakNow(
                text = breathAtHalfWidthSpace(scheduledJihouAnnouncement(time)),
                volume = speechVolume(time),
            )
        }
    }

    fun stop() {
        testJob?.cancel()
        testJob = null
        loopJob?.cancel()
        loopJob = null
        releasePlayerSync()
        announcementGate.setChimeSequenceActive(false)
    }

    fun release() {
        stopVolumePreview()
        stop()
        duckJob?.cancel()
        duckJob = null
        scope.cancel()
    }

    private suspend fun awaitAndPlayNext() {
        val now = LocalDateTime.now(zoneId)
        val target = nextChimeTime(now)
        val quarter = quarterIndexForMinute(target.minute) ?: return
        val season = currentSeason(target)
        val resId = season.soundResId(quarter)
        val introResId = season.introSoundResId(target.hour, target.minute)
        val chimeDurationMs = durationMs(resId).coerceAtLeast(1L)
        val introDurationMs = introResId?.let { durationMs(it).coerceAtLeast(1L) } ?: 0L
        val targetMillis = target.atZone(zoneId).toInstant().toEpochMilli()
        val chimeStartMillis = targetMillis - chimeDurationMs
        val sequenceStartMillis = chimeStartMillis - introDurationMs
        val announceKey = target.toString()

        // 無音プライム分だけ早めに起こす。イントロ付きはプレイヤー生成の遅れで
        // 2曲目の頭を削らないよう、さらに手前で用意する。
        val armMillis = sequenceStartMillis - PrimeSilenceMs
        var millis = System.currentTimeMillis()
        val wakeAt = if (introResId != null) {
            minOf(armMillis, sequenceStartMillis - PrepareLeadMs)
        } else {
            armMillis
        }
        if (millis < wakeAt) {
            delay(wakeAt - millis)
            millis = System.currentTimeMillis()
        }
        if (!enabled) {
            return
        }

        var externalFetchStarted = false
        fun startExternalFetch() {
            if (externalFetchStarted) {
                return
            }
            externalFetchStarted = true
            notifyChimeStarted(target)
        }

        if (millis >= targetMillis) {
            if (lastAnnouncedKey != announceKey) {
                lastAnnouncedKey = announceKey
                announcementGate.setChimeSequenceActive(true)
                try {
                    startExternalFetch()
                    speakScheduledTime(target)
                    runCatching { onAfterTimeAnnounce?.invoke(target) }
                } finally {
                    announcementGate.setChimeSequenceActive(false)
                }
            }
            delay(1_000L)
            return
        }

        // メロディは通知読み上げと並行可。読み上げ中は小さめ、完了後に戻す。
        announcementGate.setChimeSequenceActive(true)
        chimeAt = target
        try {
            var chimeQueued = false
            if (
                introResId != null &&
                millis < chimeStartMillis - MinAudibleIntroMs
            ) {
                chimeQueued = playIntroThenChimeGapless(
                    introResId = introResId,
                    introDurationMs = introDurationMs,
                    chimeResId = resId,
                    sequenceStartMillis = sequenceStartMillis,
                    chimeStartMillis = chimeStartMillis,
                )
                if (chimeQueued) {
                    startExternalFetch()
                }
                if (!enabled || suspendedByExternalInterrupt) {
                    playbackMutex.withLock { releasePlayerLocked() }
                    return
                }
            }

            if (!chimeQueued) {
                val chimeArm = chimeStartMillis - PrimeSilenceMs
                millis = System.currentTimeMillis()
                if (millis < chimeArm) {
                    delay(chimeArm - millis)
                    millis = System.currentTimeMillis()
                }
                if (millis >= targetMillis) {
                    if (lastAnnouncedKey != announceKey) {
                        lastAnnouncedKey = announceKey
                        startExternalFetch()
                        speakScheduledTime(target)
                        runCatching { onAfterTimeAnnounce?.invoke(target) }
                    }
                    return
                }
                val remainingMs = (targetMillis - millis).coerceAtLeast(1L)
                // プライム中も波形は進むので、その分だけ手前からシークする
                val seekMs = (chimeDurationMs - remainingMs - PrimeSilenceMs)
                    .coerceIn(0L, chimeDurationMs - 1L)
                val started = playbackMutex.withLock {
                    if (!enabled) {
                        false
                    } else {
                        startPrimedPlaybackLocked(resId = resId, seekMs = seekMs, warmUp = true)
                        true
                    }
                }
                if (!started) {
                    return
                }
                startExternalFetch()
            }

            millis = System.currentTimeMillis()
            if (millis < targetMillis) {
                delay(targetMillis - millis)
            }
            if (!enabled) {
                playbackMutex.withLock { releasePlayerLocked() }
                return
            }
            if (lastAnnouncedKey != announceKey) {
                lastAnnouncedKey = announceKey
                speakScheduledTime(target)
                runCatching { onAfterTimeAnnounce?.invoke(target) }
            }
            playbackMutex.withLock {
                releasePlayerLocked()
            }
        } finally {
            chimeAt = null
            announcementGate.setChimeSequenceActive(false)
        }
        delay(1_000L)
    }

    /**
     * イントロの終わりでメロディを同じ音声セッションのまま始める。
     * 曲の境でプレイヤーを作り直すと、2曲目のアタックが欠ける。
     * 成功したらメロディが終わるか、呼び出し側が読み上げ後に止めるまで手放さない。
     */
    private suspend fun playIntroThenChimeGapless(
        introResId: Int,
        introDurationMs: Long,
        chimeResId: Int,
        sequenceStartMillis: Long,
        chimeStartMillis: Long,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!enabled || now >= chimeStartMillis - MinAudibleIntroMs) {
            return false
        }
        val players = playbackMutex.withLock {
            createGaplessPairLocked(introResId, chimeResId)
        } ?: return false
        var started = false
        return try {
            val warmUpAt = sequenceStartMillis - PrimeSilenceMs
            val millis = System.currentTimeMillis()
            if (millis < warmUpAt) {
                delay(warmUpAt - millis)
            }
            if (!enabled || suspendedByExternalInterrupt) {
                return false
            }
            started = playbackMutex.withLock {
                if (mediaPlayer !== players.first || queuedPlayer !== players.second) {
                    false
                } else {
                    val at = System.currentTimeMillis()
                    if (at >= chimeStartMillis) {
                        false
                    } else {
                        val lateMs = (at - sequenceStartMillis)
                            .coerceIn(0L, introDurationMs - 1L)
                        startGaplessLocked(
                            first = players.first,
                            second = players.second,
                            firstSeekMs = lateMs,
                            warmUpFirst = lateMs == 0L,
                        )
                    }
                }
            }
            started
        } finally {
            if (!started) {
                playbackMutex.withLock {
                    if (mediaPlayer === players.first || queuedPlayer === players.second) {
                        releasePlayerLocked()
                    }
                }
            }
        }
    }

    /** テスト用。イントロからメロディまで途切れずに最後まで鳴らす。 */
    private suspend fun awaitPlayGapless(firstResId: Int, secondResId: Int) {
        val timeoutMs = durationMs(firstResId) + durationMs(secondResId) + 3_000L
        val done = CompletableDeferred<Unit>()
        val playing = playbackMutex.withLock {
            val pair = createGaplessPairLocked(firstResId, secondResId) {
                done.complete(Unit)
            } ?: return@withLock false
            currentPlaybackDone = done
            startGaplessLocked(
                first = pair.first,
                second = pair.second,
                firstSeekMs = 0L,
                warmUpFirst = true,
            )
        }
        if (!playing) {
            playbackMutex.withLock {
                if (currentPlaybackDone === done) {
                    currentPlaybackDone = null
                }
                releasePlayerLocked()
            }
            awaitPlayFull(firstResId, warmUp = true)
            awaitPlayFull(secondResId, warmUp = false)
            return
        }
        try {
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            playbackMutex.withLock {
                if (currentPlaybackDone === done) {
                    currentPlaybackDone = null
                }
                releasePlayerLocked()
            }
        }
    }

    private fun createGaplessPairLocked(
        firstResId: Int,
        secondResId: Int,
        onSecondEnded: (() -> Unit)? = null,
    ): GaplessPlayers? {
        releasePlayerLocked()
        val sessionId = newAudioSessionId()
        val first = createReadyPlayer(firstResId, sessionId, muted = true)
        val second = createReadyPlayer(secondResId, sessionId, muted = false)
        if (first == null || second == null) {
            releaseOne(first)
            releaseOne(second)
            return null
        }
        mediaPlayer = first
        queuedPlayer = second
        attachGaplessCleanup(first, second, onSecondEnded)
        return GaplessPlayers(first, second)
    }

    private fun attachGaplessCleanup(
        first: MediaPlayer,
        second: MediaPlayer,
        onSecondEnded: (() -> Unit)?,
    ) {
        first.setOnCompletionListener {
            scope.launch {
                playbackMutex.withLock {
                    // 1曲目はここでは手放さない。共有セッションの2曲目の頭を切らないため。
                    if (queuedPlayer === second) {
                        second.setVolume(playbackVolume(), playbackVolume())
                    }
                }
            }
        }
        first.setOnErrorListener { _, _, _ ->
            onSecondEnded?.invoke()
            scope.launch {
                playbackMutex.withLock {
                    if (mediaPlayer === first) {
                        releasePlayerLocked()
                    }
                }
            }
            true
        }
        second.setOnCompletionListener {
            onSecondEnded?.invoke()
            if (onSecondEnded == null) {
                scope.launch {
                    playbackMutex.withLock {
                        if (mediaPlayer === second || queuedPlayer === second) {
                            releasePlayerLocked()
                        }
                    }
                }
            }
        }
        second.setOnErrorListener { _, _, _ ->
            onSecondEnded?.invoke()
            scope.launch {
                playbackMutex.withLock { releasePlayerLocked() }
            }
            true
        }
    }

    /**
     * 1曲目の終了で2曲目を同じ出力のまま始める。
     * 2曲目は音量0にしない。開始直前の無音指定だと先頭バッファが欠ける。
     */
    private suspend fun startGaplessLocked(
        first: MediaPlayer,
        second: MediaPlayer,
        firstSeekMs: Long,
        warmUpFirst: Boolean,
    ): Boolean {
        if (mediaPlayer !== first || queuedPlayer !== second) {
            return false
        }
        if (warmUpFirst) {
            first.setVolume(0f, 0f)
            if (!runCatching { first.start() }.isSuccess) {
                return false
            }
            delay(PrimeSilenceMs)
            if (mediaPlayer !== first) {
                return false
            }
            runCatching { first.pause() }
            awaitSeek(first, 0L)
            if (mediaPlayer !== first) {
                return false
            }
        } else if (firstSeekMs > 0L) {
            awaitSeek(first, firstSeekMs)
            if (mediaPlayer !== first) {
                return false
            }
        }
        val volume = playbackVolume()
        first.setVolume(volume, volume)
        second.setVolume(volume, volume)
        val linked = runCatching { first.setNextMediaPlayer(second) }.isSuccess
        if (!linked) {
            first.setOnCompletionListener {
                scope.launch {
                    playbackMutex.withLock {
                        if (queuedPlayer !== second) {
                            return@withLock
                        }
                        second.setVolume(playbackVolume(), playbackVolume())
                        runCatching { second.start() }
                    }
                }
            }
        }
        return runCatching { first.start() }.isSuccess
    }

    private suspend fun awaitSeek(player: MediaPlayer, seekMs: Long) {
        val done = CompletableDeferred<Unit>()
        player.setOnSeekCompleteListener { done.complete(Unit) }
        val requested = runCatching {
            player.seekTo(seekMs.coerceAtLeast(0L).toInt())
        }.isSuccess
        if (requested) {
            withTimeoutOrNull(200L) { done.await() }
        }
        player.setOnSeekCompleteListener(null)
    }

    private suspend fun speakScheduledTime(target: LocalDateTime) {
        speakTimeWithPriority(target)
        notifyTimeAnnounced(target)
    }

    /**
     * 正時の時刻読み上げ。通知の読み上げ中でも切り上げて割り込み、終わったら次の通知へ進める。
     */
    private suspend fun speakTimeWithPriority(target: LocalDateTime) {
        announcementGate.withPriorityLock(
            interrupt = { speaker.stop() },
        ) {
            speaker.speakNow(
                text = breathAtHalfWidthSpace(scheduledJihouAnnouncement(target)),
                volume = speechVolume(target),
            )
        }
    }

    private fun nextChimeTime(from: LocalDateTime): LocalDateTime {
        val base = from.truncatedTo(ChronoUnit.MINUTES)
        var candidate = if (from.second > 0 || from.nano > 0) {
            base.plusMinutes(1)
        } else {
            base
        }
        repeat(60) {
            if (quarterIndexForMinute(candidate.minute) != null) {
                return candidate.withSecond(0).withNano(0)
            }
            candidate = candidate.plusMinutes(1)
        }
        return base.plusHours(1).withMinute(0).withSecond(0).withNano(0)
    }

    private fun durationMs(resId: Int): Long {
        durationCache[resId]?.let { return it }
        val player = MediaPlayer.create(appContext, resId) ?: return 20_000L
        val duration = player.duration.toLong().coerceAtLeast(1L)
        player.release()
        durationCache[resId] = duration
        return duration
    }

    private suspend fun awaitPlayFull(resId: Int, warmUp: Boolean = true) {
        val done = CompletableDeferred<Unit>()
        val player = playbackMutex.withLock {
            releasePlayerLocked()
            val created = createPlayer(resId) ?: return
            created.setOnCompletionListener {
                done.complete(Unit)
            }
            created.setOnErrorListener { _, _, _ ->
                done.complete(Unit)
                true
            }
            currentPlaybackDone = done
            mediaPlayer = created
            startPrimedPlaybackOn(created, seekMs = 0L, warmUp = warmUp)
            created
        }
        try {
            done.await()
        } finally {
            playbackMutex.withLock {
                if (currentPlaybackDone === done) {
                    currentPlaybackDone = null
                }
                if (mediaPlayer === player) {
                    releasePlayerLocked()
                }
            }
        }
    }

    private suspend fun startPrimedPlaybackLocked(
        resId: Int,
        seekMs: Long,
        warmUp: Boolean,
    ) {
        releasePlayerLocked()
        val player = createPlayer(resId) ?: return
        player.setOnCompletionListener {
            scope.launch {
                playbackMutex.withLock {
                    if (mediaPlayer === player) {
                        releasePlayerLocked()
                    }
                }
            }
        }
        mediaPlayer = player
        startPrimedPlaybackOn(player, seekMs = seekMs, warmUp = warmUp)
    }

    /**
     * [warmUp] 時は無音で少し再生してデコーダ／出力を温めてから音量を上げる。
     * 連続する2曲目はここを使わず、ギャップレス接続で頭が欠けないようにする。
     */
    private suspend fun startPrimedPlaybackOn(
        player: MediaPlayer,
        seekMs: Long,
        warmUp: Boolean,
    ) {
        if (seekMs > 0L) {
            val seekDone = CompletableDeferred<Unit>()
            player.setOnSeekCompleteListener {
                seekDone.complete(Unit)
            }
            player.setVolume(0f, 0f)
            player.seekTo(seekMs.toInt())
            withTimeoutOrNull(2_000L) { seekDone.await() }
            if (mediaPlayer !== player) {
                return
            }
        }
        if (warmUp) {
            player.setVolume(0f, 0f)
            runCatching { player.start() }
            delay(PrimeSilenceMs)
            if (mediaPlayer !== player) {
                return
            }
            applyPlaybackVolumeLocked(ducked = announcementGate.speechActive.value)
        } else {
            applyPlaybackVolumeLocked(ducked = announcementGate.speechActive.value)
            runCatching { player.start() }
        }
    }

    private fun playbackVolume(
        ducked: Boolean = announcementGate.speechActive.value,
    ): Float {
        val base = if (ducked) DuckVolume else NormalVolume
        val at = chimeAt ?: LocalDateTime.now(zoneId)
        val scale = if (testPlayback) 1f else playbackScale(at).coerceIn(0f, 1f)
        return base * scale
    }

    private fun speechVolume(at: LocalDateTime): Float {
        val scale = if (testPlayback) 1f else playbackScale(at).coerceIn(0f, 1f)
        return SpeechVolume * scale
    }

    private fun applyPlaybackVolumeLocked(ducked: Boolean) {
        val volume = playbackVolume(ducked)
        mediaPlayer?.let { runCatching { it.setVolume(volume, volume) } }
        queuedPlayer?.let { runCatching { it.setVolume(volume, volume) } }
    }

    private fun createPlayer(resId: Int, muted: Boolean = true): MediaPlayer? {
        val player = MediaPlayer.create(appContext, resId) ?: return null
        player.setAudioAttributes(chimeAudioAttributes())
        val volume = if (muted) 0f else playbackVolume()
        player.setVolume(volume, volume)
        return player
    }

    /**
     * 連続再生用。同じセッションで prepare し、失敗したら通常生成に戻す。
     * 2曲目は最初から聞こえる音量にする。開始直前に 0 から上げると先頭が欠ける。
     */
    private fun createReadyPlayer(resId: Int, sessionId: Int, muted: Boolean): MediaPlayer? {
        createPreparedPlayer(resId, sessionId, muted)?.let { return it }
        if (sessionId > 0) {
            createPreparedPlayer(resId, sessionId = 0, muted)?.let { return it }
        }
        return createPlayer(resId, muted)
    }

    private fun createPreparedPlayer(
        resId: Int,
        sessionId: Int,
        muted: Boolean,
    ): MediaPlayer? {
        val afd = runCatching { appContext.resources.openRawResourceFd(resId) }.getOrNull()
            ?: return null
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(chimeAudioAttributes())
            if (sessionId > 0) {
                player.setAudioSessionId(sessionId)
            }
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            player.prepare()
            val volume = if (muted) 0f else playbackVolume()
            player.setVolume(volume, volume)
            player
        } catch (_: Exception) {
            player.runCatching { release() }
            null
        } finally {
            afd.runCatching { close() }
        }
    }

    private fun chimeAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private fun newAudioSessionId(): Int {
        val manager = appContext.getSystemService(AudioManager::class.java) ?: return 0
        val id = manager.generateAudioSessionId()
        return if (id > 0) id else 0
    }

    private fun releasePlayerLocked() {
        val current = mediaPlayer
        val queued = queuedPlayer
        mediaPlayer = null
        queuedPlayer = null
        current?.runCatching { setNextMediaPlayer(null) }
        if (queued != null && queued !== current) {
            releaseOne(queued)
        }
        releaseOne(current)
    }

    private fun releaseOne(player: MediaPlayer?) {
        if (player == null) {
            return
        }
        runCatching { player.setOnCompletionListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { player.setOnSeekCompleteListener(null) }
        runCatching { player.setNextMediaPlayer(null) }
        runCatching {
            if (player.isPlaying) {
                player.stop()
            }
        }
        runCatching { player.release() }
    }

    private fun releasePlayerSync() {
        releasePlayerLocked()
    }

    private data class GaplessPlayers(
        val first: MediaPlayer,
        val second: MediaPlayer,
    )

    private companion object {
        /** 無音のまま先に再生して出力経路を温める時間 */
        const val PrimeSilenceMs = 80L
        /** イントロ付き時報で、再生開始前に両方のプレイヤーを用意する余裕 */
        const val PrepareLeadMs = 2_000L
        /** イントロを鳴らす最低残り時間（これ未満なら省略） */
        const val MinAudibleIntroMs = 800L
        /** 通常時の時報メロディ音量（他の効果音より少し控えめ） */
        const val NormalVolume = 0.72f
        /** 通知読み上げ中の時報メロディ音量 */
        const val DuckVolume = 0.16f
        /** 時報の時刻読み上げ音量 */
        const val SpeechVolume = 0.78f
        const val PreviewChimeMs = 2_000L
    }
}
