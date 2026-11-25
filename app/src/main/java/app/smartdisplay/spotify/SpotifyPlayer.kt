package app.smartdisplay.spotify

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PictureInPictureParams
import android.media.browse.MediaBrowser
import android.os.Build
import android.os.Looper
import android.util.Rational
import android.provider.Settings
import app.smartdisplay.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * 再生先の名前を Spotify Connect のデバイスに対応させ、再生・一時停止・曲送りを送る。
 * この端末は、まず画面を出さずに起こす。
 * それでも出ないときは小さな画面を残して Spotify を開き、準備できたら戻す。
 * 小さな画面を残さないと、この Android では裏に回ったアプリを前面へ戻せない。
 */
internal class SpotifyPlayer(context: Context) {
    private val appContext = context.applicationContext

    init {
        watchForegroundActivity(appContext)
    }
    private val client = SpotifyClient(appContext)
    private val playbackRevision = AtomicLong(0)
    private val returnScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var returnJob: Job? = null
    private var returnPending: PendingIntent? = null
    private var openedSpotify = false
    private var returnAfterCommand = false

    fun playbackRevision(): Long = playbackRevision.get()

    suspend fun playback(): MusicPlayback? = withContext(Dispatchers.IO) {
        if (!SpotifySession.connected.value) {
            return@withContext null
        }
        client.playback()
    }

    suspend fun artwork(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.artwork(url)
    }

    suspend fun play(
        output: MusicOutput,
        playlist: MusicPlaylist,
        deviceId: String? = null,
    ): PlaybackRead {
        val playlistUri = withContext(Dispatchers.IO) { client.playlistUri(playlist) }
        return playUri(output, playlistUri, deviceId)
    }

    suspend fun playUri(
        output: MusicOutput,
        contextUri: String,
        deviceId: String? = null,
    ): PlaybackRead {
        return returningFromSpotify {
            invalidatePlayback()
            val device = targetDevice(output, deviceId)
            val before = withContext(Dispatchers.IO) { client.playback() }
            applyModes(device.id, before)
            val sameContext = before != null &&
                before.contextUri == contextUri &&
                before.deviceId == device.id
            if (before != null && sameContext && before.playing) {
                return@returningFromSpotify PlaybackRead.Value(before)
            }
            if (sameContext) {
                try {
                    withContext(Dispatchers.IO) { client.resume(device.id) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    startOn(device, contextUri)
                }
            } else {
                startOn(device, contextUri)
            }
            settle { latest ->
                latest != null &&
                    latest.playing &&
                    latest.contextUri == contextUri &&
                    onDevice(latest, device.id) &&
                    (
                        sameContext ||
                            latest.trackUri != before?.trackUri ||
                            latest.progressMs in 0L..4_000L
                        )
            }
        }
    }

    suspend fun movePlayback(
        output: MusicOutput,
        deviceId: String?,
        keepPlaying: Boolean,
    ): PlaybackRead {
        return returningFromSpotify {
            invalidatePlayback()
            val device = targetDevice(output, deviceId)
            try {
                withContext(Dispatchers.IO) { client.transfer(device.id, play = keepPlaying) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SpotifyDeviceNotFound) {
                val context = withContext(Dispatchers.IO) { client.playback() }?.contextUri
                if (keepPlaying && !context.isNullOrBlank()) {
                    startOn(device, context)
                } else {
                    throw explainControlFailure(device, error)
                }
            } catch (error: Exception) {
                throw explainControlFailure(device, error)
            }
            settle { latest ->
                latest == null || latest.deviceId.isNullOrBlank() || latest.deviceId == device.id
            }
        }
    }

    suspend fun devices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        if (!SpotifySession.connected.value) {
            return@withContext emptyList()
        }
        client.devices()
    }

    suspend fun playlists(): List<SpotifyPlaylistItem> = withContext(Dispatchers.IO) {
        if (!SpotifySession.connected.value) {
            return@withContext emptyList()
        }
        client.playlists()
    }

    fun outputFor(playback: MusicPlayback, all: List<SpotifyDevice>): MusicOutput? {
        val listed = all.firstOrNull { it.id == playback.deviceId }
        if (listed != null) {
            return presetFor(listed)
        }
        val id = playback.deviceId
        if (!id.isNullOrBlank() && id == SpotifySession.localDeviceId(appContext)) {
            return MusicOutput.ThisDevice
        }
        val name = playback.deviceName?.trim().orEmpty()
        if (name.isEmpty()) {
            return null
        }
        if (localDeviceNames(appContext).any { it.equals(name, ignoreCase = true) }) {
            return MusicOutput.ThisDevice
        }
        return namedOutputs().firstOrNull { namesMatch(name, it.spotifyName.orEmpty()) }
    }

    fun isCastDevice(device: SpotifyDevice): Boolean {
        return device.type.contains("cast", ignoreCase = true)
    }

    fun otherDevices(all: List<SpotifyDevice>): List<SpotifyDevice> {
        return all.filter { device ->
            device.id.isNotBlank() &&
                !device.privateSession &&
                !isCastDevice(device) &&
                !isLocalDevice(device)
        }
    }

    private fun presetFor(device: SpotifyDevice): MusicOutput? {
        if (device.id.isNotBlank() && device.id == SpotifySession.localDeviceId(appContext)) {
            return MusicOutput.ThisDevice
        }
        if (localDeviceNames(appContext).any { it.equals(device.name, ignoreCase = true) }) {
            return MusicOutput.ThisDevice
        }
        return namedOutputs().firstOrNull { namesMatch(device.name, it.spotifyName.orEmpty()) }
    }

    private fun namedOutputs(): List<MusicOutput> {
        return MusicOutput.entries
            .filter { !it.spotifyName.isNullOrBlank() }
            .sortedByDescending { it.spotifyName.orEmpty().length }
    }

    suspend fun setShuffle(deviceId: String, enabled: Boolean): PlaybackRead {
        invalidatePlayback()
        withContext(Dispatchers.IO) { client.shuffle(deviceId, enabled) }
        return settle { latest -> latest?.shuffle == enabled }
    }

    suspend fun setRepeatAll(deviceId: String, enabled: Boolean): PlaybackRead {
        invalidatePlayback()
        withContext(Dispatchers.IO) { client.repeatAll(deviceId, enabled) }
        return settle { latest -> latest?.repeatAll == enabled }
    }

    private suspend fun applyModes(deviceId: String, current: MusicPlayback?) {
        val shuffle = SpotifySession.shuffleOn(appContext)
        val repeatAll = SpotifySession.repeatAll(appContext)
        val currentPlayback = current
        if (
            currentPlayback != null &&
            currentPlayback.deviceId == deviceId &&
            currentPlayback.shuffle == shuffle &&
            currentPlayback.repeatAll == repeatAll
        ) {
            return
        }
        try {
            withContext(Dispatchers.IO) {
                client.shuffle(deviceId, shuffle)
                client.repeatAll(deviceId, repeatAll)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    suspend fun pause(output: MusicOutput, deviceId: String? = null): PlaybackRead {
        return returningFromSpotify {
            invalidatePlayback()
            val device = targetDevice(output, deviceId)
            withContext(Dispatchers.IO) { client.pause(device.id) }
            settle { latest ->
                latest == null || !latest.playing
            }
        }
    }

    suspend fun next(output: MusicOutput, deviceId: String? = null): PlaybackRead {
        return returningFromSpotify {
            invalidatePlayback()
            val device = targetDevice(output, deviceId)
            val before = withContext(Dispatchers.IO) { client.playback() }
            withContext(Dispatchers.IO) { client.next(device.id) }
            settle { latest -> trackMoved(before, latest) }
        }
    }

    suspend fun previous(output: MusicOutput, deviceId: String? = null): PlaybackRead {
        return returningFromSpotify {
            invalidatePlayback()
            val device = targetDevice(output, deviceId)
            val before = withContext(Dispatchers.IO) { client.playback() }
            withContext(Dispatchers.IO) { client.previous(device.id) }
            settle { latest -> trackMoved(before, latest) }
        }
    }

    private suspend fun <T> returningFromSpotify(block: suspend () -> T): T {
        openedSpotify = false
        returnAfterCommand = false
        return try {
            block()
        } finally {
            val launched = openedSpotify
            val localCommand = returnAfterCommand
            openedSpotify = false
            returnAfterCommand = false
            if (launched || (localCommand && !appInFront())) {
                scheduleReturnToApp(0L)
            }
        }
    }

    /**
     * 再生状況は操作の直後だと、切り替わる前の曲のまま返ることがある。
     * 期待した状態になるまで読み直してから表示する。
     * Spotify の画面を開いているときは、再生の確認が終わるまで戻さない。
     */
    private suspend fun settle(accept: (MusicPlayback?) -> Boolean): PlaybackRead {
        if (!openedSpotify && returnAfterCommand && !appInFront()) {
            scheduleReturnToApp(0L)
        }
        val deadline = System.currentTimeMillis() + SettleTimeoutMs
        var latest: PlaybackRead = PlaybackRead.Unchanged
        while (true) {
            delay(SettleIntervalMs)
            val read = try {
                PlaybackRead.Value(withContext(Dispatchers.IO) { client.playback() })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (read != null) {
                latest = read
                if (accept(read.playback) || System.currentTimeMillis() >= deadline) {
                    return read
                }
            } else if (System.currentTimeMillis() >= deadline) {
                return latest
            }
        }
    }

    private fun invalidatePlayback() {
        playbackRevision.incrementAndGet()
    }

    private fun onDevice(latest: MusicPlayback, deviceId: String): Boolean {
        val reported = latest.deviceId
        return reported.isNullOrBlank() || reported == deviceId
    }

    private fun trackMoved(before: MusicPlayback?, latest: MusicPlayback?): Boolean {
        val uri = latest?.trackUri?.takeIf { it.isNotBlank() } ?: return false
        if (uri != before?.trackUri) {
            return true
        }
        val beforeProgress = before?.progressMs ?: -1L
        if (beforeProgress < 3_000L || latest.progressMs < 0L) {
            return false
        }
        return latest.progressMs + 1_500L < beforeProgress
    }

    private suspend fun startOn(device: SpotifyDevice, playlistUri: String) {
        try {
            start(device.id, playlistUri)
        } catch (error: SpotifyDeviceNotFound) {
            try {
                withContext(Dispatchers.IO) { client.transfer(device.id, play = false) }
            } catch (transferError: CancellationException) {
                throw transferError
            } catch (transferError: Exception) {
                throw explainControlFailure(device, transferError)
            }
            try {
                start(device.id, playlistUri)
            } catch (retry: CancellationException) {
                throw retry
            } catch (retry: Exception) {
                throw explainControlFailure(device, retry)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw explainControlFailure(device, error)
        }
    }

    private fun explainControlFailure(device: SpotifyDevice, error: Exception): Exception {
        if (error.message != "この再生先は操作できません") {
            return error
        }
        val name = device.name.ifBlank { "この再生先" }
        if (device.type.contains("cast", ignoreCase = true)) {
            return IllegalStateException(
                "Google Cast「$name」は、Spotifyアプリでその端末への再生を始めてから操作できます。このアプリから再生を開始することは Spotify が許可していません。",
            )
        }
        return IllegalStateException("「$name」は Spotify の制限で切り替えられません。")
    }

    private suspend fun start(deviceId: String, playlistUri: String) {
        try {
            withContext(Dispatchers.IO) { client.playContext(deviceId, playlistUri) }
        } catch (_: SpotifyDeviceNotFound) {
            delay(1_000)
            withContext(Dispatchers.IO) { client.playContext(deviceId, playlistUri) }
        }
    }

    private suspend fun targetDevice(
        output: MusicOutput,
        deviceId: String?,
    ): SpotifyDevice {
        if (deviceId.isNullOrBlank()) {
            return resolveDevice(output)
        }
        val found = withContext(Dispatchers.IO) { client.devices() }.firstOrNull { it.id == deviceId }
        if (found != null) {
            if (isLocalDevice(found)) {
                prepareLocalReturn()
            }
            return found
        }
        if (deviceId == SpotifySession.localDeviceId(appContext)) {
            prepareLocalReturn()
        }
        return SpotifyDevice(
            id = deviceId,
            name = "",
            type = "",
            active = false,
            restricted = false,
            privateSession = false,
        )
    }

    private suspend fun resolveDevice(output: MusicOutput): SpotifyDevice {
        val name = output.spotifyName
        if (name != null) {
            val devices = withContext(Dispatchers.IO) { client.devices() }
            val device = pickNamed(devices, name)
            return device
        }
        return resolveThisDevice()
    }

    private suspend fun resolveThisDevice(): SpotifyDevice {
        prepareLocalReturn()
        val names = localDeviceNames(appContext)
        val savedId = SpotifySession.localDeviceId(appContext)
        val existing = withContext(Dispatchers.IO) { client.devices() }
        pickLocal(existing, names, savedId)?.let { device ->
            SpotifySession.saveLocalDeviceId(appContext, device.id)
            return device
        }
        val beforeIds = existing.map { it.id }.toSet()
        wakeSpotifyProcess()
        awaitLocalDevice(names, savedId, beforeIds, BackgroundWakeWaitMs)?.let { return it }
        launchSpotify()
        if (openedSpotify || hasPendingPipLaunch()) {
            awaitLocalDevice(names, savedId, beforeIds, LocalDeviceWaitMs)?.let { return it }
        }
        val expected = names.firstOrNull().orEmpty()
        if (expected.isEmpty()) {
            throw IllegalStateException("この端末が再生先に見つかりません")
        }
        throw IllegalStateException("この端末が再生先に見つかりません。Spotify の端末名が「$expected」か確認してください")
    }

    private suspend fun awaitLocalDevice(
        names: List<String>,
        savedId: String?,
        beforeIds: Set<String>,
        waitMs: Long,
    ): SpotifyDevice? {
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            delay(LocalDevicePollMs)
            val devices = withContext(Dispatchers.IO) { client.devices() }
            val found = pickLocal(devices, names, savedId) ?: devices.singleNewLocal(beforeIds)
            if (found != null) {
                SpotifySession.saveLocalDeviceId(appContext, found.id)
                return found
            }
        }
        return null
    }

    /**
     * 画面を出さずに Spotify のプロセスを起こす。
     * 再生サービスが繋がれば、この端末が再生先に現れる。
     */
    private suspend fun wakeSpotifyProcess() {
        withContext(Dispatchers.Main.immediate) {
            val current = spotifyBrowser
            if (current != null && current.isConnected) {
                return@withContext
            }
            current?.let { running ->
                runCatching { running.disconnect() }
            }
            val connected = CompletableDeferred<Unit>()
            val browser = MediaBrowser(
                appContext,
                ComponentName(SpotifyPackage, SpotifyBrowserService),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        if (!connected.isCompleted) {
                            connected.complete(Unit)
                        }
                    }

                    override fun onConnectionFailed() {
                        if (!connected.isCompleted) {
                            connected.complete(Unit)
                        }
                    }
                },
                null,
            )
            spotifyBrowser = browser
            val started = runCatching { browser.connect() }.isSuccess
            if (!started) {
                spotifyBrowser = null
                return@withContext
            }
            withTimeoutOrNull(BackgroundConnectMs) { connected.await() }
        }
    }

    private fun launchSpotify() {
        val activity = foregroundActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return
        val launch = activity.packageManager.getLaunchIntentForPackage(SpotifyPackage)
            ?: throw IllegalStateException("Spotify アプリが見つかりません")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        pendingPipLaunch = launch
        pendingPipPlayer = this
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        val entered = runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) {
            pendingPipLaunch = null
            pendingPipPlayer = null
            return
        }
        if (pendingPipLaunch != null) {
            onEnteredPictureInPicture(activity)
        }
    }

    private fun appInFront(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false
        }
        val activity = foregroundActivity?.get() ?: return false
        return !activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()
    }

    private fun scheduleReturnToApp(initialDelayMs: Long) {
        returnJob?.cancel()
        returnJob = returnScope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            bringOurAppForward()
            for (wait in ReturnRetryDelaysMs) {
                delay(wait)
                bringOurAppForward()
            }
        }
    }

    private fun prepareLocalReturn() {
        returnAfterCommand = true
        armReturnPending()
    }

    private fun isLocalDevice(device: SpotifyDevice): Boolean {
        if (device.id.isNotBlank() && device.id == SpotifySession.localDeviceId(appContext)) {
            return true
        }
        return localDeviceNames(appContext).any { it.equals(device.name, ignoreCase = true) }
    }

    /**
     * 小さな画面が残っているあいだに、その画面から自分を前面へ戻す。
     * 画面が無い状態からの起動は、この Android では捨てられる。
     */
    private fun bringOurAppForward() {
        val activity = pipActivity?.get()?.takeUnless { it.isDestroyed }
            ?: foregroundActivity?.get()?.takeUnless { it.isDestroyed }
        if (activity != null) {
            runCatching { activity.startActivity(returnIntent()) }
            return
        }
        val pending = returnPending
        if (pending != null) {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                options.pendingIntentBackgroundActivityStartMode = backgroundStartMode()
            }
            runCatching {
                pending.send(appContext, 0, null, null, null, null, options.toBundle())
            }
        }
        runCatching { appContext.startActivity(returnIntent()) }
    }

    private fun armReturnPending() {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(backgroundStartMode())
        }
        returnPending = PendingIntent.getActivity(
            appContext,
            ReturnRequestCode,
            returnIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle(),
        )
    }

    @Suppress("DEPRECATION")
    private fun backgroundStartMode(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
    }

    private fun returnIntent(): Intent {
        return Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
    }

    private fun pickNamed(devices: List<SpotifyDevice>, name: String): SpotifyDevice {
        val candidates = devices.filter { device ->
            device.id.isNotBlank() && namesMatch(device.name, name)
        }
        val exact = candidates.filter { normalizeDeviceName(it.name) == normalizeDeviceName(name) }
        val named = exact.ifEmpty { candidates }
        val matches = named.filter { !it.privateSession }
        if (matches.isEmpty()) {
            if (named.isNotEmpty()) {
                throw IllegalStateException("「$name」は操作できません")
            }
            val visible = devices.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
            if (visible.isEmpty()) {
                throw IllegalStateException("「$name」が再生先に見つかりません")
            }
            val listed = visible.joinToString("、") { "「$it」" }
            throw IllegalStateException("「$name」が再生先に見つかりません。見えているのは${listed}です")
        }
        val open = matches.filter { !it.restricted }
        val pool = open.ifEmpty { matches }
        return pool.firstOrNull { it.active } ?: pool.first()
    }

    private fun namesMatch(deviceName: String, expected: String): Boolean {
        val left = normalizeDeviceName(deviceName)
        val right = normalizeDeviceName(expected)
        return left == right || left.endsWith(" $right")
    }

    private fun normalizeDeviceName(value: String): String {
        return value
            .replace("\u200b", "")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun pickLocal(
        devices: List<SpotifyDevice>,
        names: List<String>,
        savedId: String?,
    ): SpotifyDevice? {
        val usable = devices.filter { it.usable }
        if (!savedId.isNullOrBlank()) {
            usable.firstOrNull { it.id == savedId }?.let { return it }
        }
        usable.firstOrNull { device -> names.any { it == device.name } }?.let { return it }
        return usable.firstOrNull { device ->
            names.any { it.equals(device.name, ignoreCase = true) }
        }
    }

    private fun List<SpotifyDevice>.singleNewLocal(beforeIds: Set<String>): SpotifyDevice? {
        val newcomers = filter { device ->
            device.usable && device.id !in beforeIds && device.type in LocalDeviceTypes
        }
        return newcomers.singleOrNull()
    }

    private fun localDeviceNames(context: Context): List<String> {
        val resolver = context.contentResolver
        val values = listOf(
            runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
            runCatching { Settings.Secure.getString(resolver, "bluetooth_name") }.getOrNull(),
            runCatching { Settings.System.getString(resolver, "device_name") }.getOrNull(),
            Build.MODEL,
        )
        return values.mapNotNull { value ->
            value?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
    }

    companion object {
        internal fun noteForegroundActivity(activity: Activity?) {
            foregroundActivity = activity?.let { WeakReference(it) }
        }

        private const val SpotifyPackage = "com.spotify.music"
        private const val SpotifyBrowserService =
            "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService"
        private const val LocalDeviceWaitMs = 15_000L
        private const val LocalDevicePollMs = 1_000L
        private const val BackgroundWakeWaitMs = 4_000L
        private const val BackgroundConnectMs = 2_000L
        private const val SettleIntervalMs = 500L
        private const val SettleTimeoutMs = 4_000L
        private val ReturnRetryDelaysMs = longArrayOf(400L, 900L, 1_600L)
        private const val ReturnRequestCode = 7101
        private val LocalDeviceTypes = setOf("Computer", "Smartphone", "Tablet")
        private var watchingForeground = false
        private var foregroundActivity: WeakReference<Activity>? = null
        private var pipActivity: WeakReference<Activity>? = null
        private var pendingPipLaunch: Intent? = null
        private var pendingPipPlayer: SpotifyPlayer? = null
        private var spotifyBrowser: MediaBrowser? = null

        internal fun onEnteredPictureInPicture(activity: Activity) {
            pipActivity = WeakReference(activity)
            foregroundActivity = WeakReference(activity)
            val launch = pendingPipLaunch ?: return
            val player = pendingPipPlayer ?: return
            pendingPipLaunch = null
            pendingPipPlayer = null
            runCatching { activity.startActivity(launch) }
            player.openedSpotify = true
        }

        private fun hasPendingPipLaunch(): Boolean = pendingPipLaunch != null

        private fun watchForegroundActivity(context: Context) {
            if (watchingForeground) {
                return
            }
            val app = context.applicationContext as? Application ?: return
            watchingForeground = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    foregroundActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (activity.isInPictureInPictureMode) {
                        foregroundActivity = WeakReference(activity)
                        pipActivity = WeakReference(activity)
                        return
                    }
                    if (foregroundActivity?.get() === activity) {
                        foregroundActivity = null
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    if (foregroundActivity?.get() === activity) {
                        foregroundActivity = null
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            })
        }
    }
}
