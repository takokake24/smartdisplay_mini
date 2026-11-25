package app.smartdisplay.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.smartdisplay.media.MediaNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音声優先に選んだアプリが MediaSession で再生中かどうかを見る。
 * 通知音は通常セッションを持たないため、対象外になる。
 */
class PriorityAudioMonitor(
    context: Context,
    private val settings: AudioPrioritySettings,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, MediaNotificationListener::class.java)

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var started = false
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val trackedControllers = mutableMapOf<MediaController, MediaController.Callback>()
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!started) {
                return
            }
            recompute()
            handler.postDelayed(this, RefreshIntervalMs)
        }
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        val manager = mediaSessionManager
        if (manager != null) {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener {
                recompute()
            }
            sessionsListener = listener
            runCatching {
                manager.addOnActiveSessionsChangedListener(listener, listenerComponent, handler)
            }
        }
        recompute()
        handler.postDelayed(refreshRunnable, RefreshIntervalMs)
    }

    /** 優先アプリの選択が変わったときにすぐ再判定する。 */
    fun refresh() {
        if (!started) {
            return
        }
        recompute()
    }

    fun release() {
        if (!started) {
            return
        }
        started = false
        handler.removeCallbacks(refreshRunnable)
        sessionsListener?.let { listener ->
            mediaSessionManager?.runCatching { removeOnActiveSessionsChangedListener(listener) }
        }
        sessionsListener = null
        clearTrackedControllers()
        _active.value = false
    }

    private fun recompute() {
        if (Looper.myLooper() != handler.looper) {
            handler.post { recompute() }
            return
        }
        if (!started) {
            _active.value = false
            return
        }
        val packages = settings.priorityPackages
        if (packages.isEmpty()) {
            clearTrackedControllers()
            _active.value = false
            return
        }
        val manager = mediaSessionManager
        val sessions = if (manager == null) {
            emptyList()
        } else {
            runCatching { manager.getActiveSessions(listenerComponent) }.getOrNull().orEmpty()
        }
        syncTrackedControllers(sessions)
        val playing = sessions.any { controller ->
            controller.packageName in packages && isPlayingState(controller.playbackState?.state)
        }
        _active.value = playing
    }

    private fun syncTrackedControllers(sessions: List<MediaController>) {
        val keep = sessions.toSet()
        val stale = trackedControllers.keys.filter { it !in keep }
        for (controller in stale) {
            val callback = trackedControllers.remove(controller) ?: continue
            runCatching { controller.unregisterCallback(callback) }
        }
        for (controller in sessions) {
            if (controller in trackedControllers) {
                continue
            }
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = recompute()

                override fun onSessionDestroyed() = recompute()
            }
            runCatching { controller.registerCallback(callback, handler) }
                .onSuccess { trackedControllers[controller] = callback }
        }
    }

    private fun clearTrackedControllers() {
        for ((controller, callback) in trackedControllers) {
            runCatching { controller.unregisterCallback(callback) }
        }
        trackedControllers.clear()
    }

    private companion object {
        const val RefreshIntervalMs = 2_000L

        fun isPlayingState(state: Int?): Boolean {
            return when (state) {
                PlaybackState.STATE_PLAYING,
                PlaybackState.STATE_BUFFERING,
                PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_SKIPPING_TO_NEXT,
                PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING,
                -> true
                else -> false
            }
        }
    }
}
