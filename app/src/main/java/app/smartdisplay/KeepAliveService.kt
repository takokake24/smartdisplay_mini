package app.smartdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * mini固有: アプリ非表示中に時報・通知読み上げを続けるための常駐通知。
 * 表示中は出さない。休止中は「ONにする」で外出中も鳴らすと同じ効果。
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionExit -> {
                KeepAliveRuntime.from(this).exitApp()
                return START_NOT_STICKY
            }
            ActionEnableAway -> {
                val runtime = KeepAliveRuntime.from(this)
                runtime.enableAwaySound()
                if (!runtime.shouldShowNotification()) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                startAsForeground(runtime.presenceGate.mayPlay)
                return START_NOT_STICKY
            }
            ActionHide -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                val runtime = KeepAliveRuntime.from(this)
                if (!runtime.shouldShowNotification()) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                val mayPlay = intent?.getBooleanExtra(ExtraMayPlay, true) ?: true
                startAsForeground(mayPlay)
                return START_NOT_STICKY
            }
        }
    }

    private fun startAsForeground(mayPlay: Boolean) {
        ensureChannel()
        val notification = buildNotification(mayPlay)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NotificationId, notification, type)
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) {
            return
        }
        val channel = NotificationChannel(
            ChannelId,
            getString(R.string.keep_alive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keep_alive_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(mayPlay: Boolean): Notification {
        val title = getString(
            if (mayPlay) {
                R.string.keep_alive_title_active
            } else {
                R.string.keep_alive_title_paused
            },
        )
        val openApp = PendingIntent.getActivity(
            this,
            RequestOpen,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            this,
            RequestExit,
            Intent(this, KeepAliveService::class.java).setAction(ActionExit),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_keep_alive)
            .setContentTitle(title)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!mayPlay) {
            val enableAway = PendingIntent.getService(
                this,
                RequestEnableAway,
                Intent(this, KeepAliveService::class.java).setAction(ActionEnableAway),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.keep_alive_action_enable), enableAway)
        }
        builder.addAction(0, getString(R.string.keep_alive_action_exit), exit)
        return builder.build()
    }

    companion object {
        private const val ChannelId = "keep_alive"
        private const val NotificationId = 42
        private const val ExtraMayPlay = "may_play"
        private const val ActionShow = "app.smartdisplay.action.KEEP_ALIVE_SHOW"
        private const val ActionHide = "app.smartdisplay.action.KEEP_ALIVE_HIDE"
        private const val ActionExit = "app.smartdisplay.action.KEEP_ALIVE_EXIT"
        private const val ActionEnableAway = "app.smartdisplay.action.KEEP_ALIVE_ENABLE_AWAY"
        private const val RequestOpen = 1
        private const val RequestExit = 2
        private const val RequestEnableAway = 3

        fun show(context: Context, mayPlay: Boolean) {
            val app = context.applicationContext
            val intent = Intent(app, KeepAliveService::class.java)
                .setAction(ActionShow)
                .putExtra(ExtraMayPlay, mayPlay)
            ContextCompat.startForegroundService(app, intent)
        }

        fun refresh(context: Context, mayPlay: Boolean) {
            show(context, mayPlay)
        }

        fun hide(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, KeepAliveService::class.java).setAction(ActionHide)
            runCatching { app.startService(intent) }
                .onFailure {
                    app.getSystemService(NotificationManager::class.java)?.cancel(NotificationId)
                }
        }
    }
}
