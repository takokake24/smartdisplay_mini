package app.smartdisplay.jihou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.smartdisplay.calendar.CalendarAnnounceKind
import app.smartdisplay.calendar.CalendarAnnouncer
import app.smartdisplay.notification.NotificationCategorySunset
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.notification.NotificationSoundEffect
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.switchbot.SwitchBotClient
import app.smartdisplay.switchbot.curtainDeviceNames
import app.smartdisplay.switchbot.switchBotConfigured
import app.smartdisplay.switchbot.switchBotSecret
import app.smartdisplay.switchbot.switchBotToken
import app.smartdisplay.weather.WeatherAnnounceKind
import app.smartdisplay.weather.WeatherAnnouncer
import app.smartdisplay.weather.WeatherRepository
import app.smartdisplay.util.PreparedFetch
import app.smartdisplay.util.launchRetryLoop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 日の入りの時刻に通知し、読み上げのあと「このあとの天気」「このあとの予定」を続ける。
 * 時報と重なるときは、その分のメロディと時刻の読み上げのあとに案内する。時刻は次の分へずらさない。
 * 時報が鳴り始めた時点で、カーテン位置などの取得を始める。
 * 通知履歴には残さない。
 */
class SunsetAnnouncer(
    private val repository: WeatherRepository,
    private val notifications: NotificationCenter,
    private val weatherAnnouncer: WeatherAnnouncer,
    private val calendarAnnouncer: CalendarAnnouncer,
    private val announcementGate: AnnouncementGate,
    private val chimeEnabled: () -> Boolean,
    private val playOverlapChime: suspend (hour: Int, minute: Int) -> Unit,
    private val switchBot: SwitchBotClient = SwitchBotClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deliverMutex = Mutex()
    private var loopJob: Job? = null
    private var testJob: Job? = null
    private var announcedDate: LocalDate? = null
    private var cachedDay: LocalDate? = null
    private var cachedSunset: LocalTime? = null
    private val chimeTimeSpoken = MutableStateFlow<LocalDateTime?>(null)
    private val curtainPrepared = PreparedFetch<Boolean>()

    fun start() {
        if (loopJob?.isActive == true) {
            return
        }
        loopJob = scope.launchRetryLoop(PollIntervalMs) { awaitAndAnnounce() }
    }

    fun playTest() {
        if (testJob?.isActive == true) {
            return
        }
        testJob = scope.launch {
            val sunset = resolveSunset(LocalDateTime.now())
            if (sunset == null) {
                notifications.postReadoutFailure(
                    title = "日の入り",
                    message = "日の入りの時刻を取得できませんでした。",
                )
                return@launch
            }
            val overlaps = overlapsChime(sunset)
            deliver(
                sunset,
                chimeAlreadySpoke = overlaps,
                playChimeFirst = overlaps,
            )
        }
    }

    fun release() {
        loopJob?.cancel()
        loopJob = null
        testJob?.cancel()
        testJob = null
        scope.cancel()
    }

    private suspend fun awaitAndAnnounce() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        if (announcedDate == today) {
            delay(PollIntervalMs)
            return
        }
        val sunset = resolveSunset(now)
        if (sunset == null) {
            delay(PollIntervalMs)
            return
        }
        val target = today.atTime(sunset)
        val untilMs = ChronoUnit.MILLIS.between(now, target)
        if (untilMs > 0) {
            delay(untilMs.coerceAtMost(PollIntervalMs))
            return
        }
        val lateSec = ChronoUnit.SECONDS.between(target, LocalDateTime.now())
        if (lateSec > GraceSeconds) {
            announcedDate = today
            delay(PollIntervalMs)
            return
        }
        if (overlapsChime(sunset)) {
            val spoke = withTimeoutOrNull(ChimeHandoffMs) {
                chimeTimeSpoken.first { chimeSpokeSunsetMinute(it, sunset, today) }
            }
            if (spoke != null) {
                announcementGate.awaitChimeSequenceIdle()
                announcedDate = today
                deliver(sunset, chimeAlreadySpoke = true, playChimeFirst = false)
                return
            }
        }
        announcedDate = today
        deliver(sunset, chimeAlreadySpoke = false, playChimeFirst = false)
    }

    /** 時報が、この分の時刻を読み終えた。 */
    fun noteChimeTime(time: LocalDateTime) {
        chimeTimeSpoken.value = time
    }

    private fun resolveSunset(now: LocalDateTime): LocalTime? {
        val today = now.toLocalDate()
        val loaded = repository.cachedForecast(now)
            ?.daily
            ?.firstOrNull { it.date == today }
            ?.sunset
        if (loaded != null) {
            cachedDay = today
            cachedSunset = loaded
            return loaded
        }
        return cachedSunset?.takeIf { cachedDay == today }
    }

    private fun overlapsChime(sunset: LocalTime): Boolean {
        return chimeEnabled() && quarterIndexForMinute(sunset.minute) != null
    }

    /** この時報の時刻が、今日の日の入りと重なる。 */
    fun matchesChime(time: LocalDateTime): Boolean {
        val sunset = resolveSunset(time) ?: return false
        return sunset.hour == time.hour && sunset.minute == time.minute
    }

    /** 時報の鳴り始めに、カーテン位置の取得を始める。 */
    fun prepareCurtain() {
        curtainPrepared.start(CurtainKey) { anyCurtainOpen() }
    }

    private suspend fun deliver(
        sunset: LocalTime,
        chimeAlreadySpoke: Boolean,
        playChimeFirst: Boolean,
    ) {
        // テストで時報と重なるときは、先にバナーを出して読み上げだけ時報の後に回す。
        if (playChimeFirst) {
            announcementGate.setChimeSequenceActive(true)
        }
        try {
            val curtainWillClose = curtainPrepared.take(CurtainKey) ?: anyCurtainOpen()
            deliverMutex.withLock {
                notifications.post(
                    category = NotificationCategorySunset,
                    title = sunsetAnnouncementTitle(),
                    body = sunsetAnnouncementBody(sunset, curtainWillClose),
                    speechText = sunsetAnnouncementSpeech(
                        sunset,
                        curtainWillClose = curtainWillClose,
                        afterChime = chimeAlreadySpoke,
                    ),
                    alert = true,
                    addToHistory = false,
                    soundEffect = if (chimeAlreadySpoke) {
                        NotificationSoundEffect.None
                    } else {
                        NotificationSoundEffect.Sunset
                    },
                    priority = true,
                )
                if (playChimeFirst) {
                    playOverlapChime(sunset.hour, sunset.minute)
                }
                weatherAnnouncer.postAnnouncement(
                    WeatherAnnounceKind.RestOfToday,
                    includeIndoorOutdoor = true,
                )
                calendarAnnouncer.postAnnouncement(CalendarAnnounceKind.RestOfToday)
            }
        } finally {
            if (playChimeFirst) {
                announcementGate.setChimeSequenceActive(false)
            }
        }
    }

    /**
     * リビング・ベッド・デスクのどれかが全閉でなければ、閉まる注意を出す。
     * 位置を読めないときも注意を残す。
     */
    private suspend fun anyCurtainOpen(): Boolean {
        if (!switchBotConfigured) {
            return true
        }
        val names = curtainDeviceNames()
        return withContext(Dispatchers.IO) {
            try {
                val ids = switchBot.fetchInventory(switchBotToken, switchBotSecret).deviceIds
                val deviceIds = names.map { name -> ids[name] }
                if (deviceIds.any { it.isNullOrBlank() }) {
                    return@withContext true
                }
                val positions = deviceIds.map { id ->
                    async {
                        runCatching {
                            switchBot.fetchSlidePosition(switchBotToken, switchBotSecret, id!!)
                        }.getOrElse { error ->
                            if (error is CancellationException) {
                                throw error
                            }
                            null
                        }
                    }
                }.awaitAll()
                positions.any { it != CurtainClosedPosition }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                true
            }
        }
    }

    private companion object {
        const val PollIntervalMs = 30_000L
        /** 起動が日の入り直後でも知らせる猶予。これより遅い起動はその日を飛ばす。 */
        const val GraceSeconds = 90L
        /** 時報がこの分の時刻を読み終えるのを待つ。過ぎたら日の入り側で時刻も読む。 */
        const val ChimeHandoffMs = 15_000L
        /** SwitchBot の slidePosition。0 が全開、100 が全閉。 */
        const val CurtainClosedPosition = 100
    }
}

private object CurtainKey

private fun chimeSpokeSunsetMinute(
    spoken: LocalDateTime?,
    sunset: LocalTime,
    day: LocalDate,
): Boolean {
    return spoken != null &&
        spoken.toLocalDate() == day &&
        spoken.hour == sunset.hour &&
        spoken.minute == sunset.minute
}

fun sunsetAnnouncementTitle(): String = "日の入りの時間です。"

fun sunsetAnnouncementBody(time: LocalTime, curtainWillClose: Boolean): String {
    val clock = "時刻は、${time.format(SunsetClockFormat)}です。"
    if (!curtainWillClose) {
        return clock
    }
    return "カーテンが閉まりますので、ご注意ください。\n$clock"
}

fun sunsetAnnouncementSpeech(
    time: LocalTime,
    curtainWillClose: Boolean,
    afterChime: Boolean = false,
): String {
    val opening = if (curtainWillClose) {
        "日の入りの時間です。カーテンが閉まりますので、ご注意ください。"
    } else {
        "日の入りの時間です。"
    }
    if (afterChime) {
        return opening
    }
    return "${opening}時刻は、${sunsetSpeechClock(time)}です。"
}

private fun sunsetSpeechClock(time: LocalTime): String {
    return if (time.minute == 0) {
        "${time.hour}時"
    } else {
        "${time.hour}時${time.minute}分"
    }
}

private val SunsetClockFormat = DateTimeFormatter.ofPattern("H:mm")

@Composable
fun rememberSunsetAnnouncer(
    repository: WeatherRepository,
    notifications: NotificationCenter,
    weatherAnnouncer: WeatherAnnouncer,
    calendarAnnouncer: CalendarAnnouncer,
    announcementGate: AnnouncementGate,
    chimeEnabled: () -> Boolean,
    playOverlapChime: suspend (hour: Int, minute: Int) -> Unit,
): SunsetAnnouncer {
    val announcer = remember(
        repository,
        notifications,
        weatherAnnouncer,
        calendarAnnouncer,
        announcementGate,
    ) {
        SunsetAnnouncer(
            repository = repository,
            notifications = notifications,
            weatherAnnouncer = weatherAnnouncer,
            calendarAnnouncer = calendarAnnouncer,
            announcementGate = announcementGate,
            chimeEnabled = chimeEnabled,
            playOverlapChime = playOverlapChime,
        )
    }
    DisposableEffect(announcer) {
        announcer.start()
        onDispose { announcer.release() }
    }
    return announcer
}
