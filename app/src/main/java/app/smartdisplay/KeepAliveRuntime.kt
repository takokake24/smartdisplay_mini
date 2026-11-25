package app.smartdisplay

import android.app.Application
import android.content.Context
import app.smartdisplay.audio.AudioPrioritySettings
import app.smartdisplay.audio.EffectPlayer
import app.smartdisplay.audio.LocalPlaybackDucker
import app.smartdisplay.audio.PriorityAudioMonitor
import app.smartdisplay.audio.SoundPresenceGate
import app.smartdisplay.battery.BatteryAnnounceSettings
import app.smartdisplay.battery.BatteryAnnouncer
import app.smartdisplay.jihou.HourlyChimeController
import app.smartdisplay.jihou.JihouSettings
import app.smartdisplay.notification.DeviceNotificationAnnouncer
import app.smartdisplay.notification.NotificationAnnounceSettings
import app.smartdisplay.notification.NotificationCenter
import app.smartdisplay.speech.AnnouncementGate
import app.smartdisplay.speech.SpeechSpeaker
import kotlin.system.exitProcess

/**
 * mini固有: 時報・通知読み上げを Activity 表示中以外でも動かすための共有ランタイム。
 * 常駐通知の表示切替と、「外出中も鳴らす」「終了」もここ経由で扱う。
 */
class KeepAliveRuntime(private val app: Application) {
    private val lock = Any()
    private var started = false
    private var exiting = false
    private var notificationVisible = false

    lateinit var presenceGate: SoundPresenceGate
        private set
    lateinit var effects: EffectPlayer
        private set
    lateinit var speechSpeaker: SpeechSpeaker
        private set
    lateinit var announcementGate: AnnouncementGate
        private set
    lateinit var jihouSettings: JihouSettings
        private set
    lateinit var audioPrioritySettings: AudioPrioritySettings
        private set
    lateinit var notificationAnnounceSettings: NotificationAnnounceSettings
        private set
    lateinit var batteryAnnounceSettings: BatteryAnnounceSettings
        private set
    lateinit var chimeController: HourlyChimeController
        private set
    lateinit var notifications: NotificationCenter
        private set
    lateinit var priorityAudioMonitor: PriorityAudioMonitor
        private set

    private lateinit var playbackDucker: LocalPlaybackDucker
    private lateinit var deviceAnnouncer: DeviceNotificationAnnouncer
    private lateinit var batteryAnnouncer: BatteryAnnouncer

    fun ensureStarted(): KeepAliveRuntime {
        synchronized(lock) {
            if (exiting || started) {
                return this
            }
            presenceGate = SoundPresenceGate(app)
            effects = EffectPlayer(app)
            effects.presenceGate = presenceGate
            playbackDucker = LocalPlaybackDucker(app)
            announcementGate = AnnouncementGate { active ->
                playbackDucker.setDucked(active)
            }
            speechSpeaker = SpeechSpeaker(app)
            jihouSettings = JihouSettings(app)
            audioPrioritySettings = AudioPrioritySettings(app)
            priorityAudioMonitor = PriorityAudioMonitor(app, audioPrioritySettings)
            audioPrioritySettings.onPackagesChanged = {
                priorityAudioMonitor.refresh()
            }
            notificationAnnounceSettings = NotificationAnnounceSettings(app)
            batteryAnnounceSettings = BatteryAnnounceSettings(app)
            chimeController = HourlyChimeController(
                context = app,
                speaker = speechSpeaker,
                announcementGate = announcementGate,
                presenceGate = presenceGate,
                priorityAudioActive = priorityAudioMonitor.active,
                initiallyEnabled = jihouSettings.enabled,
            )
            notifications = NotificationCenter(
                speaker = speechSpeaker,
                effects = effects,
                announcementGate = announcementGate,
                presenceGate = presenceGate,
                priorityAudioActive = priorityAudioMonitor.active,
            )
            deviceAnnouncer = DeviceNotificationAnnouncer(
                context = app,
                notifications = notifications,
                settings = notificationAnnounceSettings,
            )
            batteryAnnouncer = BatteryAnnouncer(
                context = app,
                notifications = notifications,
                settings = batteryAnnounceSettings,
                isResident = { notificationVisible && !exiting },
            )
            presenceGate.setOnMayPlayChanged { mayPlay ->
                if (notificationVisible && !exiting) {
                    KeepAliveService.refresh(app, mayPlay)
                }
            }
            presenceGate.addOnAudibleRevoked {
                silenceAudible()
            }
            presenceGate.start()
            priorityAudioMonitor.start()
            chimeController.start()
            deviceAnnouncer.start()
            batteryAnnouncer.start()
            started = true
        }
        return this
    }

    /** Bluetooth切断や外出抑制で、再生中の読み上げ・SE・時報をすぐ止める。 */
    private fun silenceAudible() {
        if (exiting) {
            return
        }
        runCatching { speechSpeaker.stop() }
        runCatching { effects.stop() }
        runCatching { chimeController.interruptAudible() }
    }

    fun shouldShowNotification(): Boolean = notificationVisible && !exiting

    fun onAppForeground() {
        if (exiting) {
            return
        }
        notificationVisible = false
        KeepAliveService.hide(app)
    }

    fun onAppBackground() {
        if (exiting) {
            return
        }
        ensureStarted()
        notificationVisible = true
        KeepAliveService.show(app, presenceGate.mayPlay)
    }

    fun enableAwaySound() {
        if (exiting) {
            return
        }
        ensureStarted()
        presenceGate.setAwaySoundEnabled(true)
    }

    fun exitApp() {
        synchronized(lock) {
            if (exiting) {
                return
            }
            exiting = true
        }
        notificationVisible = false
        release()
        exitProcess(0)
    }

    private fun release() {
        synchronized(lock) {
            if (!started) {
                return
            }
            started = false
            runCatching { batteryAnnouncer.stop() }
            runCatching { deviceAnnouncer.stop() }
            runCatching { chimeController.release() }
            runCatching { notifications.release() }
            runCatching { speechSpeaker.release() }
            runCatching { effects.release() }
            runCatching { priorityAudioMonitor.release() }
            audioPrioritySettings.onPackagesChanged = null
            runCatching { presenceGate.release() }
            runCatching { playbackDucker.setDucked(false) }
        }
    }

    companion object {
        fun from(context: Context): KeepAliveRuntime {
            return (context.applicationContext as SmartDisplayApp).keepAlive
        }
    }
}
