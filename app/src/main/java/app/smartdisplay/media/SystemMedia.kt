package app.smartdisplay.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.smartdisplay.notification.DeviceNotificationSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemMedia(
    val id: String,
    val packageName: String,
    val label: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val remote: Boolean = false,
    val artwork: ImageBitmap?,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canShuffle: Boolean,
    val canRepeat: Boolean,
    val shuffle: Boolean,
    val repeatAll: Boolean,
    val positionMs: Long = -1L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val positionUpdatedAt: Long = 0L,
)

/**
 * 通知のメディア操作に出ている、他アプリの再生。
 * 一覧を取るには、このアプリを通知へのアクセスで許可しておく。
 */
class SystemMediaHub(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val manager = appContext.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(appContext, MediaNotificationListener::class.java)
    private val controllers = linkedMapOf<String, MediaController>()
    private val callbacks = mutableMapOf<String, MediaController.Callback>()
    private val artworkCache = mutableMapOf<String, Pair<Bitmap, ImageBitmap>>()
    private val labels = mutableMapOf<String, String>()
    private val castControllers = linkedMapOf<String, MediaController>()
    private val castTitles = mutableMapOf<String, String>()
    private var castCard: SystemMedia? = null
    private var castWatching = false
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        replace(sessions ?: emptyList())
    }
    private val _sessions = MutableStateFlow<List<SystemMedia>>(emptyList())
    val sessions: StateFlow<List<SystemMedia>> = _sessions.asStateFlow()
    private val _deviceNowPlaying = MutableStateFlow<SystemMedia?>(null)
    val deviceNowPlaying: StateFlow<SystemMedia?> = _deviceNowPlaying.asStateFlow()
    private val _listenerEnabled = MutableStateFlow(false)
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()
    private val _sessionsReady = MutableStateFlow(false)
    val sessionsReady: StateFlow<Boolean> = _sessionsReady.asStateFlow()
    private var started = false

    fun start() {
        if (!castWatching) {
            MediaNotificationListener.watch(this)
            castWatching = true
        }
        refresh()
    }

    fun onHomeNotificationsChanged() {
        handler.post {
            if (!listenerEnabledNow()) {
                return@post
            }
            syncCastNotifications()
            publish()
        }
    }

    fun refresh() {
        val enabled = listenerEnabledNow()
        _listenerEnabled.value = enabled
        if (!enabled || manager == null) {
            detach()
            _sessionsReady.value = true
            return
        }
        NotificationListenerService.requestRebind(component)
        if (!started) {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                started = true
            } catch (_: SecurityException) {
                started = false
                _sessionsReady.value = true
                return
            }
        }
        try {
            replace(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            detach()
        }
        if (listenerEnabledNow()) {
            syncCastNotifications()
            publish()
        }
        _sessionsReady.value = true
    }

    fun stop() {
        if (castWatching) {
            MediaNotificationListener.unwatch(this)
            castWatching = false
        }
        detach()
    }

    fun hasNotificationAccess(): Boolean = listenerEnabledNow()

    fun notificationAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    component.flattenToString(),
                )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    fun openAccessSettings() {
        appContext.startActivity(
            notificationAccessIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun detach() {
        if (started) {
            started = false
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        }
        clearControllers()
        clearCastControllers()
        _sessions.value = emptyList()
        _deviceNowPlaying.value = null
    }

    private fun listenerEnabledNow(): Boolean {
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':').any { flat ->
            ComponentName.unflattenFromString(flat) == component
        }
    }

    fun play(id: String) {
        runCatching { controls(id)?.play() }
    }

    fun pause(id: String) {
        runCatching { controls(id)?.pause() }
    }

    fun next(id: String) {
        runCatching { controls(id)?.skipToNext() }
    }

    fun previous(id: String) {
        runCatching { controls(id)?.skipToPrevious() }
    }

    fun setShuffle(id: String, enabled: Boolean) {
        val controls = controls(id) ?: return
        val mode = if (enabled) ShuffleAll else ShuffleNone
        runCatching {
            controls.javaClass.getMethod("setShuffleMode", Int::class.javaPrimitiveType)
                .invoke(controls, mode)
        }
    }

    fun setRepeatAll(id: String, enabled: Boolean) {
        val controls = controls(id) ?: return
        val mode = if (enabled) RepeatAll else RepeatNone
        runCatching {
            controls.javaClass.getMethod("setRepeatMode", Int::class.javaPrimitiveType)
                .invoke(controls, mode)
        }
    }

    private fun controls(id: String): MediaController.TransportControls? {
        return (controllers[id] ?: castControllers[id])?.transportControls
    }

    private fun replace(sessions: List<MediaController>) {
        val orderedIds = mutableListOf<String>()
        sessions.forEach { controller ->
            if (!shouldKeep(controller)) {
                return@forEach
            }
            val id = sessionId(controller)
            orderedIds += id
            if (id !in controllers) {
                controllers[id] = controller
                watch(id, controller)
            }
        }
        val keep = orderedIds.toSet()
        (controllers.keys - keep).forEach { id ->
            release(id)
            controllers.remove(id)
        }
        val ordered = linkedMapOf<String, MediaController>()
        orderedIds.forEach { id ->
            controllers[id]?.let { ordered[id] = it }
        }
        controllers.clear()
        controllers.putAll(ordered)
        publish()
    }

    private fun syncCastNotifications() {
        val posted = runCatching { MediaNotificationListener.connected?.activeNotifications }
            .getOrNull()
            .orEmpty()
        val knownTokens = controllers.values.map { it.sessionToken }.toSet()
        val nextIds = mutableSetOf<String>()
        var card: SystemMedia? = null
        posted.forEach { notification ->
            if (!isGoogleHomeMedia(notification)) {
                return@forEach
            }
            val extras = notification.notification.extras
            val title = extras.charText(Notification.EXTRA_TITLE)
            val token = extras.mediaToken()
            if (token == null || token in knownTokens) {
                if (card == null && token == null) {
                    card = castCard(notification.key, notification.packageName, title, extras)
                }
                return@forEach
            }
            val id = "cast:" + token.hashCode()
            nextIds += id
            castTitles[id] = title
            if (id !in castControllers) {
                val controller = MediaController(appContext, token)
                castControllers[id] = controller
                watch(id, controller, sticky = true)
            }
        }
        (castControllers.keys - nextIds).forEach { id ->
            unregister(id, castControllers[id])
            castControllers.remove(id)
            castTitles.remove(id)
        }
        castCard = if (nextIds.isEmpty()) card else null
    }

    private fun isGoogleHomeMedia(notification: StatusBarNotification): Boolean {
        val posted = notification.notification
        val extras = posted.extras
        if (notification.packageName == CastServicePackage &&
            (
                notification.tag == "CastRCN" ||
                    posted.channelId == "cast_rcn_notification" ||
                    extras.containsKey("android.mediaRemoteDevice")
            )
        ) {
            return true
        }
        val home = notification.packageName == GoogleHomePackage
        val castService = notification.packageName == CastServicePackage &&
            (
                extras.mediaToken() != null ||
                    extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle")
            )
        if (!home && !castService) {
            return false
        }
        if (posted.flags and Notification.FLAG_GROUP_SUMMARY != 0 && extras.mediaToken() == null) {
            return false
        }
        val title = extras.charText(Notification.EXTRA_TITLE)
        val text = extras.charText(Notification.EXTRA_TEXT)
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val ongoing = posted.flags and Notification.FLAG_ONGOING_EVENT != 0
        return extras.mediaToken() != null ||
            template.contains("MediaStyle") ||
            posted.category == Notification.CATEGORY_TRANSPORT ||
            (title.isNotBlank() && ongoing && text.isNotBlank())
    }

    private fun castCard(
        key: String,
        packageName: String,
        title: String,
        extras: Bundle,
    ): SystemMedia? {
        val shown = title.ifBlank { extras.charText(Notification.EXTRA_TEXT) }
        if (shown.isBlank()) {
            return null
        }
        return SystemMedia(
            id = "cast-card:$key",
            packageName = packageName,
            label = appLabel(packageName),
            title = shown,
            artist = extras.charText(Notification.EXTRA_TEXT),
            playing = true,
            remote = true,
            artwork = null,
            canPrevious = false,
            canNext = false,
            canShuffle = false,
            canRepeat = false,
            shuffle = false,
            repeatAll = false,
        )
    }

    private fun clearCastControllers() {
        castControllers.keys.toList().forEach { id ->
            unregister(id, castControllers[id])
        }
        castControllers.clear()
        castTitles.clear()
        castCard = null
    }

    private fun watch(id: String, controller: MediaController, sticky: Boolean = false) {
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (!sticky) {
                    val current = controllers[id]
                    if (current == null || !shouldKeep(current, state)) {
                        unregister(id, current)
                        controllers.remove(id)
                    }
                }
                publish()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                publish()
            }

            override fun onAudioInfoChanged(playbackInfo: MediaController.PlaybackInfo) {
                publish()
            }

            override fun onSessionDestroyed() {
                unregister(id, controllers[id] ?: castControllers[id])
                controllers.remove(id)
                castControllers.remove(id)
                castTitles.remove(id)
                publish()
            }
        }
        callbacks[id] = callback
        controller.registerCallback(callback, handler)
    }

    private fun release(id: String) {
        unregister(id, controllers[id])
    }

    private fun unregister(id: String, controller: MediaController?) {
        val callback = callbacks.remove(id) ?: return
        controller?.unregisterCallback(callback)
        artworkCache.remove(id)
    }

    private fun clearControllers() {
        controllers.keys.toList().forEach { id ->
            release(id)
        }
        controllers.clear()
        artworkCache.clear()
    }

    private fun publish() {
        val sessions = controllers.map { (id, controller) ->
            snapshot(id, controller)
        }
        val casts = castControllers.map { (id, controller) ->
            snapshot(id, controller, titleOverride = castTitles[id], forceRemote = true)
        }
        val all = sessions + casts + listOfNotNull(castCard)
        _sessions.value = all
        _deviceNowPlaying.value = all.firstOrNull { it.playing && it.title.isNotBlank() }
            ?: all.firstOrNull { it.remote && it.title.isNotBlank() }
    }

    private fun snapshot(
        id: String,
        controller: MediaController,
        titleOverride: String? = null,
        forceRemote: Boolean = false,
    ): SystemMedia {
        val state = controller.playbackState
        val metadata = controller.metadata
        val actions = state?.actions ?: 0L
        val packageName = controller.packageName
        val speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        val updatedAt = state?.lastPositionUpdateTime ?: 0L
        val label = appLabel(packageName)
        return SystemMedia(
            id = id,
            packageName = packageName,
            label = label,
            title = metadata.displayTitle().ifBlank { titleOverride.orEmpty() }.ifBlank { label },
            artist = metadata.artistName(),
            playing = isPlaying(state?.state),
            remote = forceRemote || isRemotePlayback(controller),
            artwork = artwork(id, metadata),
            canPrevious = actions.has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canNext = actions.has(PlaybackState.ACTION_SKIP_TO_NEXT),
            canShuffle = actions.has(actionBit("ACTION_SET_SHUFFLE_MODE", 1L shl 21)),
            canRepeat = actions.has(actionBit("ACTION_SET_REPEAT_MODE", 1L shl 20)),
            shuffle = state.mode("getShuffleMode").let { it == ShuffleAll || it == ShuffleGroup },
            repeatAll = state.mode("getRepeatMode").let { it == RepeatAll || it == RepeatGroup },
            positionMs = state?.position ?: -1L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L,
            playbackSpeed = speed,
            positionUpdatedAt = if (updatedAt > 0L) updatedAt else SystemClock.elapsedRealtime(),
        )
    }

    private fun artwork(id: String, metadata: MediaMetadata?): ImageBitmap? {
        val source = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        artworkCache[id]?.let { (cached, image) ->
            if (cached === source) {
                return image
            }
        }
        val copy = runCatching { source.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        val image = copy.asImageBitmap()
        artworkCache[id] = source to image
        return image
    }

    private fun appLabel(packageName: String): String {
        labels[packageName]?.let { return it }
        val label = runCatching {
            val info = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: packageName.substringAfterLast('.')
        labels[packageName] = label
        return label
    }

    private fun sessionId(controller: MediaController): String {
        return controller.packageName + ":" + controller.sessionToken.hashCode()
    }

    private fun shouldKeep(
        controller: MediaController,
        state: PlaybackState? = controller.playbackState,
    ): Boolean {
        return shownInMediaControls(state) || isRemotePlayback(controller)
    }

    private fun isRemotePlayback(controller: MediaController): Boolean {
        val type = runCatching { controller.playbackInfo.playbackType }.getOrNull() ?: return false
        return type == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
    }

    private fun shownInMediaControls(state: PlaybackState?): Boolean {
        return when (state?.state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
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

    private fun isPlaying(state: Int?): Boolean {
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

    private fun Long.has(action: Long): Boolean = this and action != 0L

    private fun actionBit(name: String, fallback: Long): Long {
        return runCatching { PlaybackState::class.java.getField(name).getLong(null) }.getOrDefault(fallback)
    }

    private fun PlaybackState?.mode(method: String): Int {
        if (this == null) {
            return -1
        }
        return runCatching {
            javaClass.getMethod(method).invoke(this) as Int
        }.getOrDefault(-1)
    }

    private companion object {
        private const val GoogleHomePackage = "com.google.android.apps.chromecast.app"
        private const val CastServicePackage = "com.google.android.gms"
        private const val ShuffleNone = 0
        private const val ShuffleAll = 1
        private const val ShuffleGroup = 2
        private const val RepeatNone = 0
        private const val RepeatAll = 2
        private const val RepeatGroup = 3
    }
}

private fun MediaMetadata?.displayTitle(): String {
    if (this == null) {
        return ""
    }
    return getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.trim().orEmpty()
        .ifBlank { getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty() }
}

private fun MediaMetadata?.artistName(): String {
    if (this == null) {
        return ""
    }
    return getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        .ifBlank { getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty() }
        .ifBlank { getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.trim().orEmpty() }
        .ifBlank { getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty() }
}

private fun Bundle.charText(key: String): String {
    return getCharSequence(key)?.toString()?.trim().orEmpty()
}

private fun Bundle.mediaToken(): MediaSession.Token? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(Notification.EXTRA_MEDIA_SESSION)
    }
}

class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        deviceSinks.forEach { it.onListenerReady(active) }
        dispatch()
    }

    override fun onListenerDisconnected() {
        if (connected === this) {
            connected = null
        }
        dispatch()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val ranking = Ranking()
        val ranked = runCatching { rankingMap.getRanking(sbn.key, ranking) }.getOrDefault(false)
        deviceSinks.forEach { sink ->
            sink.onNotificationPosted(sbn, if (ranked) ranking else null)
        }
        dispatch()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        dispatch()
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        onNotificationRemoved(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) {
            deviceSinks.forEach { it.onNotificationRemoved(sbn.key) }
        }
        dispatch()
    }

    private fun dispatch() {
        hubs.forEach { it.onHomeNotificationsChanged() }
    }

    companion object {
        @Volatile
        var connected: MediaNotificationListener? = null

        private val hubs = java.util.concurrent.CopyOnWriteArrayList<SystemMediaHub>()
        private val deviceSinks = java.util.concurrent.CopyOnWriteArrayList<DeviceNotificationSink>()

        fun watch(hub: SystemMediaHub) {
            if (!hubs.contains(hub)) {
                hubs.add(hub)
            }
        }

        fun unwatch(hub: SystemMediaHub) {
            hubs.remove(hub)
        }

        fun watchDevice(sink: DeviceNotificationSink) {
            if (!deviceSinks.contains(sink)) {
                deviceSinks.add(sink)
            }
            val service = connected ?: return
            val active = runCatching { service.activeNotifications?.toList().orEmpty() }
                .getOrDefault(emptyList())
            sink.onListenerReady(active)
        }

        fun unwatchDevice(sink: DeviceNotificationSink) {
            deviceSinks.remove(sink)
        }
    }
}

@Composable
fun rememberSystemMedia(): SystemMediaHub {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hub = remember(context) { SystemMediaHub(context) }
    DisposableEffect(hub, lifecycle) {
        val resume = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hub.refresh()
            }
        }
        lifecycle.addObserver(resume)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                hub.refresh()
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor("enabled_notification_listeners"),
            false,
            observer,
        )
        hub.start()
        onDispose {
            lifecycle.removeObserver(resume)
            context.contentResolver.unregisterContentObserver(observer)
            hub.stop()
        }
    }
    return hub
}
