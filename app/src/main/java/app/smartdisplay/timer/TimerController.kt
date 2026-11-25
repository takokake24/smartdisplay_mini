package app.smartdisplay.timer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.away.awayAlertTrackResIds
import app.smartdisplay.away.releaseQuietly
import app.smartdisplay.away.takeShuffled
import app.smartdisplay.notification.NotificationCategoryAlarm
import app.smartdisplay.notification.NotificationCategoryTimer
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayDeque

enum class TimerPhase {
    Idle,
    Running,
    Ringing,
}

data class TimerUiState(
    val phase: TimerPhase = TimerPhase.Idle,
    val endEpochMillis: Long = 0L,
)

fun timerRemainingMillis(endEpochMillis: Long, now: LocalDateTime): Long {
    val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return endEpochMillis - nowMillis
}

fun formatTimerRemaining(remainingMillis: Long): String {
    val totalSec = remainingMillis.coerceAtLeast(0L) / 1000L
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

enum class AlarmPhase {
    Idle,
    Armed,
    Ringing,
}

data class AlarmUiState(
    val phase: AlarmPhase = AlarmPhase.Idle,
    val hour: Int = 7,
    val minute: Int = 0,
    val endEpochMillis: Long = 0L,
)

fun formatAlarmTime(hour: Int, minute: Int): String {
    return "%d:%02d".format(hour, minute)
}

fun alarmRingSpeech(hour: Int, minute: Int): String {
    val clock = if (minute == 0) {
        "${hour}時"
    } else {
        "${hour}時${minute}分"
    }
    return "アラームが作動しました。時刻は、${clock}です。"
}

/** 今日のその時刻がまだ先なら今日、過ぎていれば翌日。 */
fun nextAlarmEpochMillis(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
    var fire = now.toLocalDate().atTime(hour, minute).atZone(zone)
    if (!fire.toInstant().isAfter(now.toInstant())) {
        fire = fire.plusDays(1)
    }
    return fire.toInstant().toEpochMilli()
}

/** タイマー作動中とアラーム設定中を、タイマー、アラームの順で1行にする。 */
fun clockStatusLabel(timer: TimerUiState, alarm: AlarmUiState, now: LocalDateTime): String? {
    val parts = ArrayList<String>(2)
    if (timer.phase == TimerPhase.Running) {
        val remaining = timerRemainingMillis(timer.endEpochMillis, now)
        parts.add("タイマー ${formatTimerRemaining(remaining)}")
    }
    if (alarm.phase == AlarmPhase.Armed) {
        parts.add("アラーム ${formatAlarmTime(alarm.hour, alarm.minute)}")
    }
    if (parts.isEmpty()) {
        return null
    }
    return parts.joinToString("   ")
}

/**
 * アプリ内のタイマーとアラーム。
 * 作動中のタイマーと設定中のアラームは時計画面に出し、時間になると通知を出したまま先に読み上げ、そのあとアラート曲を流す。
 * 曲はシャッフルし、終わったら次に進む。自分で止めるか、鳴ってから5分で止まる。
 * 勝手に止めたときは停止を通知一覧へ追加する。画面には出さず、読み上げない。
 * 片方が鳴っているあいだにもう片方が鳴ると、先のほうを同じように止めて切り替える。
 */
class TimerController(
    context: Context,
    private val notifications: NotificationCenter,
    private val announcementGate: AnnouncementGate,
    private val speaker: SpeechSpeaker,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = TimerAlarmPlayer(appContext)
    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()
    private val _alarm = MutableStateFlow(AlarmUiState())
    val alarmState: StateFlow<AlarmUiState> = _alarm.asStateFlow()

    private var countdownJob: Job? = null
    private var alarmJob: Job? = null
    private var timerAutoStopJob: Job? = null
    private var alarmAutoStopJob: Job? = null
    private var ringingNotificationId: String? = null
    private var alarmRingingNotificationId: String? = null
    private var activeRing: RingOwner? = null
    private var announceJob: Job? = null
    private var announceOwner: RingOwner? = null
    private var announcementGeneration = 0
    private var speakingAnnouncement = false
    private var waitingForSpeech = false

    /** 時計画面が危機表示やサウンドテストを渡すまで鳴らさない。 */
    private var playbackBlocked = true
    private val playbackBlockedFlow = MutableStateFlow(true)

    init {
        scope.launch {
            combine(
                announcementGate.speechActive,
                announcementGate.chimeSequenceActive,
            ) { speech, chime ->
                speech || chime
            }.collect { audible ->
                player.setDucked(audible)
            }
        }
        restore()
    }

    fun start(durationMillis: Long) {
        if (durationMillis <= 0L) {
            return
        }
        val previousId = ringingNotificationId
        ringingNotificationId = null
        countdownJob?.cancel()
        countdownJob = null
        timerAutoStopJob?.cancel()
        timerAutoStopJob = null
        if (activeRing == RingOwner.Timer) {
            activeRing = null
            player.stop()
        }
        cancelAnnouncement(RingOwner.Timer)
        if (previousId != null) {
            notifications.remove(previousId)
        }
        arm(System.currentTimeMillis() + durationMillis)
    }

    fun stop() {
        val id = ringingNotificationId
        ringingNotificationId = null
        becomeIdle()
        if (id != null) {
            notifications.remove(id)
        }
    }

    fun startAlarm(hour: Int, minute: Int) {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val previousId = alarmRingingNotificationId
        alarmRingingNotificationId = null
        alarmJob?.cancel()
        alarmJob = null
        alarmAutoStopJob?.cancel()
        alarmAutoStopJob = null
        if (activeRing == RingOwner.Alarm) {
            activeRing = null
            player.stop()
        }
        cancelAnnouncement(RingOwner.Alarm)
        if (previousId != null) {
            notifications.remove(previousId)
        }
        armAlarm(safeHour, safeMinute, nextAlarmEpochMillis(safeHour, safeMinute))
    }

    fun stopAlarm() {
        val id = alarmRingingNotificationId
        alarmRingingNotificationId = null
        becomeAlarmIdle()
        if (id != null) {
            notifications.remove(id)
        }
    }

    fun onUserDismissed(id: String) {
        when (id) {
            ringingNotificationId -> {
                ringingNotificationId = null
                becomeIdle()
            }
            alarmRingingNotificationId -> {
                alarmRingingNotificationId = null
                becomeAlarmIdle()
            }
        }
    }

    fun setPlaybackBlocked(blocked: Boolean) {
        if (playbackBlocked == blocked) {
            return
        }
        playbackBlocked = blocked
        playbackBlockedFlow.value = blocked
        syncPlayback()
    }

    fun release() {
        announcementGeneration += 1
        announceJob?.cancel()
        announceJob = null
        if (speakingAnnouncement) {
            speakingAnnouncement = false
            speaker.stop()
        }
        countdownJob?.cancel()
        countdownJob = null
        alarmJob?.cancel()
        alarmJob = null
        player.stop()
        scope.cancel()
    }

    private fun restore() {
        restoreTimer()
        restoreAlarm()
    }

    private fun restoreTimer() {
        val phase = prefs.getString(KeyPhase, null) ?: return
        val end = prefs.getLong(KeyEnd, 0L)
        when (phase) {
            PhaseRunning -> {
                if (System.currentTimeMillis() >= end) {
                    ring(end)
                } else {
                    arm(end)
                }
            }
            PhaseRinging -> ring(
                endEpochMillis = end,
                startedAt = prefs.getLong(KeyRingStarted, System.currentTimeMillis()),
            )
        }
    }

    private fun restoreAlarm() {
        val phase = prefs.getString(KeyAlarmPhase, null) ?: return
        val hour = prefs.getInt(KeyAlarmHour, 0)
        val minute = prefs.getInt(KeyAlarmMinute, 0)
        val end = prefs.getLong(KeyAlarmEnd, 0L)
        when (phase) {
            PhaseArmed -> {
                if (System.currentTimeMillis() >= end) {
                    ringAlarm(hour, minute, end)
                } else {
                    armAlarm(hour, minute, end)
                }
            }
            PhaseRinging -> ringAlarm(
                hour = hour,
                minute = minute,
                endEpochMillis = end,
                startedAt = prefs.getLong(KeyAlarmRingStarted, System.currentTimeMillis()),
            )
        }
    }

    private fun arm(endEpochMillis: Long) {
        countdownJob?.cancel()
        _state.value = TimerUiState(TimerPhase.Running, endEpochMillis)
        prefs.edit()
            .putString(KeyPhase, PhaseRunning)
            .putLong(KeyEnd, endEpochMillis)
            .remove(KeyRingStarted)
            .apply()
        countdownJob = scope.launch {
            val wait = endEpochMillis - System.currentTimeMillis()
            if (wait > 0L) {
                delay(wait)
            }
            ring(endEpochMillis)
        }
    }

    private fun ring(endEpochMillis: Long, startedAt: Long = System.currentTimeMillis()) {
        countdownJob = null
        if (System.currentTimeMillis() - startedAt >= AutoStopMillis) {
            becomeIdle()
            postStoppedNotice(RingOwner.Timer)
            return
        }
        if (_alarm.value.phase == AlarmPhase.Ringing) {
            preemptAlarm()
        }
        _state.value = TimerUiState(TimerPhase.Ringing, endEpochMillis)
        prefs.edit()
            .putString(KeyPhase, PhaseRinging)
            .putLong(KeyEnd, endEpochMillis)
            .putLong(KeyRingStarted, startedAt)
            .apply()
        activeRing = RingOwner.Timer
        ringingNotificationId = notifications.post(
            category = NotificationCategoryTimer,
            title = "タイマー",
            body = "時間になりました",
            speechText = "",
            alert = true,
            soundEffect = NotificationSoundEffect.None,
            skipSpeech = true,
            priority = true,
            holdUntilDismiss = true,
        )
        playAfterAnnouncement(RingOwner.Timer, TimerRingSpeech)
        scheduleAutoStop(
            startedAt = startedAt,
            stillRinging = { _state.value.phase == TimerPhase.Ringing },
            assignJob = { timerAutoStopJob = it },
            clearId = {
                val id = ringingNotificationId
                ringingNotificationId = null
                timerAutoStopJob = null
                id
            },
            finish = { becomeIdle() },
            owner = RingOwner.Timer,
        )
    }

    private fun armAlarm(hour: Int, minute: Int, endEpochMillis: Long) {
        alarmJob?.cancel()
        _alarm.value = AlarmUiState(AlarmPhase.Armed, hour, minute, endEpochMillis)
        prefs.edit()
            .putString(KeyAlarmPhase, PhaseArmed)
            .putInt(KeyAlarmHour, hour)
            .putInt(KeyAlarmMinute, minute)
            .putLong(KeyAlarmEnd, endEpochMillis)
            .remove(KeyAlarmRingStarted)
            .apply()
        alarmJob = scope.launch {
            val wait = endEpochMillis - System.currentTimeMillis()
            if (wait > 0L) {
                delay(wait)
            }
            ringAlarm(hour, minute, endEpochMillis)
        }
    }

    private fun ringAlarm(
        hour: Int,
        minute: Int,
        endEpochMillis: Long,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        alarmJob = null
        if (System.currentTimeMillis() - startedAt >= AutoStopMillis) {
            becomeAlarmIdle()
            postStoppedNotice(RingOwner.Alarm)
            return
        }
        if (_state.value.phase != TimerPhase.Idle) {
            preemptTimer()
        }
        _alarm.value = AlarmUiState(AlarmPhase.Ringing, hour, minute, endEpochMillis)
        prefs.edit()
            .putString(KeyAlarmPhase, PhaseRinging)
            .putInt(KeyAlarmHour, hour)
            .putInt(KeyAlarmMinute, minute)
            .putLong(KeyAlarmEnd, endEpochMillis)
            .putLong(KeyAlarmRingStarted, startedAt)
            .apply()
        activeRing = RingOwner.Alarm
        alarmRingingNotificationId = notifications.post(
            category = NotificationCategoryAlarm,
            title = "アラーム",
            body = "時間になりました",
            speechText = "",
            alert = true,
            soundEffect = NotificationSoundEffect.None,
            skipSpeech = true,
            priority = true,
            holdUntilDismiss = true,
        )
        playAfterAnnouncement(RingOwner.Alarm, alarmRingSpeech(hour, minute))
        scheduleAutoStop(
            startedAt = startedAt,
            stillRinging = { _alarm.value.phase == AlarmPhase.Ringing },
            assignJob = { alarmAutoStopJob = it },
            clearId = {
                val id = alarmRingingNotificationId
                alarmRingingNotificationId = null
                alarmAutoStopJob = null
                id
            },
            finish = { becomeAlarmIdle() },
            owner = RingOwner.Alarm,
        )
    }

    private fun scheduleAutoStop(
        startedAt: Long,
        stillRinging: () -> Boolean,
        assignJob: (Job?) -> Unit,
        clearId: () -> String?,
        finish: () -> Unit,
        owner: RingOwner,
    ) {
        val remain = AutoStopMillis - (System.currentTimeMillis() - startedAt)
        val job = scope.launch {
            if (remain > 0L) {
                delay(remain)
            }
            if (!stillRinging()) {
                return@launch
            }
            val id = clearId()
            finish()
            if (id != null) {
                notifications.remove(id)
            }
            postStoppedNotice(owner)
        }
        assignJob(job)
    }

    private fun preemptTimer() {
        if (_state.value.phase == TimerPhase.Idle) {
            return
        }
        val id = ringingNotificationId
        ringingNotificationId = null
        becomeIdle()
        if (id != null) {
            notifications.remove(id)
        }
        postStoppedNotice(RingOwner.Timer)
    }

    private fun preemptAlarm() {
        if (_alarm.value.phase != AlarmPhase.Ringing) {
            return
        }
        val id = alarmRingingNotificationId
        alarmRingingNotificationId = null
        becomeAlarmIdle()
        if (id != null) {
            notifications.remove(id)
        }
        postStoppedNotice(RingOwner.Alarm)
    }

    private fun postStoppedNotice(owner: RingOwner) {
        val category: String
        val title: String
        val body: String
        when (owner) {
            RingOwner.Timer -> {
                category = NotificationCategoryTimer
                title = "タイマー"
                body = "タイマーが停止しました。"
            }
            RingOwner.Alarm -> {
                category = NotificationCategoryAlarm
                title = "アラーム"
                body = "アラームが停止しました。"
            }
        }
        notifications.post(
            category = category,
            title = title,
            body = body,
            speechText = "",
            alert = false,
            soundEffect = NotificationSoundEffect.None,
            skipSpeech = true,
        )
    }

    private fun playAfterAnnouncement(owner: RingOwner, speech: String) {
        announcementGeneration += 1
        val generation = announcementGeneration
        val previous = announceJob
        announceJob = null
        previous?.cancel()
        if (speakingAnnouncement) {
            speakingAnnouncement = false
            speaker.stop()
        }
        announceOwner = owner
        waitingForSpeech = true
        announceJob = scope.launch {
            try {
                var spoken = false
                while (announcementGeneration == generation && activeRing == owner && !spoken) {
                    playbackBlockedFlow.first { !it }
                    if (announcementGeneration != generation || activeRing != owner) {
                        return@launch
                    }
                    announcementGate.awaitChimeSequenceIdle()
                    if (announcementGeneration != generation || activeRing != owner) {
                        return@launch
                    }
                    spoken = announcementGate.withLock {
                        if (announcementGeneration != generation || activeRing != owner) {
                            false
                        } else if (!announcementGate.shouldContinueSpeech()) {
                            false
                        } else {
                            speakingAnnouncement = true
                            try {
                                speaker.speakNow(speech)
                            } finally {
                                if (announcementGeneration == generation) {
                                    speakingAnnouncement = false
                                }
                            }
                        }
                    }
                    if (!spoken &&
                        announcementGate.shouldContinueSpeech() &&
                        !announcementGate.chimeSequenceActive.value
                    ) {
                        break
                    }
                }
                if (announcementGeneration != generation || activeRing != owner) {
                    return@launch
                }
                announcementGate.awaitChimeSequenceIdle()
                playbackBlockedFlow.first { !it }
                if (announcementGeneration != generation || activeRing != owner) {
                    return@launch
                }
                waitingForSpeech = false
                syncPlayback()
            } finally {
                if (announcementGeneration == generation) {
                    waitingForSpeech = false
                    announceOwner = null
                }
            }
        }
    }

    private fun cancelAnnouncement(owner: RingOwner) {
        if (announceOwner != owner) {
            return
        }
        announcementGeneration += 1
        announceOwner = null
        waitingForSpeech = false
        val job = announceJob
        announceJob = null
        job?.cancel()
        if (speakingAnnouncement) {
            speakingAnnouncement = false
            speaker.stop()
        }
    }

    private fun becomeIdle() {
        countdownJob?.cancel()
        countdownJob = null
        timerAutoStopJob?.cancel()
        timerAutoStopJob = null
        cancelAnnouncement(RingOwner.Timer)
        if (activeRing == RingOwner.Timer) {
            activeRing = null
            player.stop()
        }
        _state.value = TimerUiState()
        prefs.edit().remove(KeyPhase).remove(KeyEnd).remove(KeyRingStarted).apply()
        syncPlayback()
    }

    private fun becomeAlarmIdle() {
        alarmJob?.cancel()
        alarmJob = null
        alarmAutoStopJob?.cancel()
        alarmAutoStopJob = null
        cancelAnnouncement(RingOwner.Alarm)
        if (activeRing == RingOwner.Alarm) {
            activeRing = null
            player.stop()
        }
        _alarm.value = AlarmUiState()
        prefs.edit()
            .remove(KeyAlarmPhase)
            .remove(KeyAlarmHour)
            .remove(KeyAlarmMinute)
            .remove(KeyAlarmEnd)
            .remove(KeyAlarmRingStarted)
            .apply()
        syncPlayback()
    }

    private fun syncPlayback() {
        if (activeRing != null && !playbackBlocked && !waitingForSpeech) {
            player.ensurePlaying()
        } else {
            player.pause()
        }
    }

    private enum class RingOwner {
        Timer,
        Alarm,
    }

    private companion object {
        const val PrefsName = "timer"
        const val KeyPhase = "phase"
        const val KeyEnd = "end_epoch"
        const val KeyRingStarted = "ring_started"
        const val KeyAlarmPhase = "alarm_phase"
        const val KeyAlarmHour = "alarm_hour"
        const val KeyAlarmMinute = "alarm_minute"
        const val KeyAlarmEnd = "alarm_end"
        const val KeyAlarmRingStarted = "alarm_ring_started"
        const val PhaseRunning = "running"
        const val PhaseArmed = "armed"
        const val PhaseRinging = "ringing"
        const val AutoStopMillis = 5 * 60 * 1000L
        const val TimerRingSpeech = "タイマーが終了しました。"
    }
}

/**
 * 鳴動中のアラーム。外出アラートと同じ曲をシャッフルし、終わったら次を鳴らす。
 * 一度流した曲は、残りの曲が全部終わるまで繰り返さない。
 */
private class TimerAlarmPlayer(context: Context) {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val tracks: List<Int> = awayAlertTrackResIds()
    private val order = ArrayDeque<Int>()
    private var player: MediaPlayer? = null
    private var wantPlay = false
    private var ducked = false
    private var focusHeld = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(mediaAttributes())
        .setOnAudioFocusChangeListener { change ->
            if (!wantPlay) {
                return@setOnAudioFocusChangeListener
            }
            if (change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                handler.post {
                    if (wantPlay) {
                        requestFocus()
                    }
                }
            }
        }
        .build()

    fun ensurePlaying() {
        wantPlay = true
        val current = player
        if (current == null) {
            startNew()
            return
        }
        applyVolume()
        requestFocus()
        runCatching {
            if (!current.isPlaying) {
                current.start()
            }
        }
    }

    fun pause() {
        wantPlay = false
        val current = player ?: run {
            abandonFocus()
            return
        }
        runCatching {
            if (current.isPlaying) {
                current.pause()
            }
        }
        abandonFocus()
    }

    fun stop() {
        wantPlay = false
        ducked = false
        order.clear()
        abandonFocus()
        releasePlayer()
    }

    fun setDucked(value: Boolean) {
        ducked = value
        applyVolume()
    }

    private fun startNew() {
        if (!wantPlay) {
            return
        }
        val resId = nextTrack() ?: return
        val created = runCatching { MediaPlayer.create(appContext, resId) }.getOrNull() ?: return
        created.setAudioAttributes(mediaAttributes())
        created.setOnCompletionListener { finished ->
            finished.setOnCompletionListener(null)
            handler.post {
                if (player !== finished) {
                    return@post
                }
                releasePlayer()
                if (wantPlay) {
                    startNew()
                }
            }
        }
        created.setOnErrorListener { failed, _, _ ->
            handler.post {
                if (player === failed) {
                    releasePlayer()
                }
            }
            true
        }
        player = created
        applyVolume()
        requestFocus()
        runCatching { created.start() }
    }

    private fun nextTrack(): Int? = order.takeShuffled(tracks)

    private fun applyVolume() {
        val volume = if (ducked) DuckVolume else 1f
        player?.setVolume(volume, volume)
    }

    private fun requestFocus() {
        val manager = audio ?: return
        val result = manager.requestAudioFocus(focusRequest)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!focusHeld) {
            return
        }
        focusHeld = false
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    private fun releasePlayer() {
        val current = player ?: return
        player = null
        current.releaseQuietly()
    }

    private companion object {
        const val DuckVolume = 0.22f

        fun mediaAttributes(): AudioAttributes {
            return AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }
    }
}

@Composable
fun rememberTimerController(
    notifications: NotificationCenter,
    announcementGate: AnnouncementGate,
    speaker: SpeechSpeaker,
): TimerController {
    val context = LocalContext.current
    val controller = remember(notifications, announcementGate, speaker) {
        TimerController(
            context = context.applicationContext,
            notifications = notifications,
            announcementGate = announcementGate,
            speaker = speaker,
        )
    }
    DisposableEffect(controller) {
        notifications.setUserDismissListener { id -> controller.onUserDismissed(id) }
        onDispose {
            notifications.setUserDismissListener(null)
            controller.release()
        }
    }
    return controller
}
