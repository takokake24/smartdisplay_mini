package app.smartdisplay.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * この端末で鳴っている他の再生（Spotify アプリなど）を一時的に下げる。
 * メディア音量そのものは変えないので、時報や通知の音量はそのまま残る。
 * 別の再生先で鳴っている音には影響しない。
 * 案内の途中から再生が始まると、その再生がフォーカスを取る。そのときは取り直して下げる。
 */
class LocalPlaybackDucker(context: Context) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var wantDuck = false
    private var held = false
    private var requesting = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
        .build()
    private val acquireAgain = Runnable { acquire() }

    fun setDucked(ducked: Boolean) {
        val manager = audio ?: return
        synchronized(lock) {
            wantDuck = ducked
            handler.removeCallbacks(acquireAgain)
            if (!ducked) {
                if (held) {
                    manager.abandonAudioFocusRequest(focusRequest)
                }
                held = false
                return
            }
        }
        acquire()
    }

    private fun onFocusChange(change: Int) {
        if (requesting) {
            return
        }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                synchronized(lock) {
                    held = false
                    if (!wantDuck) {
                        return
                    }
                }
                scheduleAcquire()
            }
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            -> synchronized(lock) { held = true }
        }
    }

    private fun scheduleAcquire() {
        handler.removeCallbacks(acquireAgain)
        handler.postDelayed(acquireAgain, ReacquireDelayMs)
    }

    private fun acquire() {
        val manager = audio ?: return
        synchronized(lock) {
            if (!wantDuck) {
                return
            }
            requesting = true
        }
        val result = try {
            manager.requestAudioFocus(focusRequest)
        } finally {
            synchronized(lock) { requesting = false }
        }
        synchronized(lock) {
            if (!wantDuck) {
                manager.abandonAudioFocusRequest(focusRequest)
                held = false
                return
            }
            held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!held) {
                handler.removeCallbacks(acquireAgain)
                handler.postDelayed(acquireAgain, ReacquireDelayMs)
            }
        }
    }

    private companion object {
        const val ReacquireDelayMs = 250L
    }
}
