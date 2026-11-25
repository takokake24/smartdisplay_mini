package app.smartdisplay.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.session.MediaSession
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import app.smartdisplay.media.MediaNotificationListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 他アプリの新規通知を、右半分のバナーと読み上げに載せる。
 * 読み上げを切っているときは載せない。音の出ない通知は、設定で含めるときだけ載せる。
 * 再生コントロールと、Chrome の「サイトで〜が使用されています」「共有されています」、
 * Spotify の「Spotifyの接続」、Bose の「同期を維持します。」は対象にしない。
 * 音の出る通知は端末の通知音に任せ、こちらでは鳴らさない。
 * 音の出ない通知は共通の通知音を鳴らしてから読む。
 */
interface DeviceNotificationSink {
    fun onListenerReady(active: List<StatusBarNotification>)
    fun onNotificationPosted(sbn: StatusBarNotification, ranking: Ranking?)
    fun onNotificationRemoved(key: String)
}

class DeviceNotificationAnnouncer(
    context: Context,
    private val notifications: NotificationCenter,
    private val settings: NotificationAnnounceSettings,
) : DeviceNotificationSink {
    private val appContext = context.applicationContext
    private val packageName = appContext.packageName
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val seen = HashMap<String, String>()
    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Bitmap?>()
    private val durationCache = HashMap<String, Long>()
    private var recentSpeech = ""
    private var recentSpeechAt = 0L

    fun start() {
        MediaNotificationListener.watchDevice(this)
    }

    fun stop() {
        MediaNotificationListener.unwatchDevice(this)
        scope.cancel()
    }

    override fun onListenerReady(active: List<StatusBarNotification>) {
        val next = HashMap<String, String>()
        for (sbn in active) {
            val fingerprint = fingerprintOf(sbn) ?: continue
            next[sbn.key] = fingerprint
        }
        synchronized(seen) {
            seen.clear()
            seen.putAll(next)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, ranking: Ranking?) {
        val captured = capture(sbn, ranking) ?: return
        val announce = synchronized(seen) {
            val previous = seen.put(captured.key, captured.fingerprint)
            shouldAnnounce(captured, previous)
        }
        if (!announce) {
            return
        }
        scope.launch { publish(captured) }
    }

    override fun onNotificationRemoved(key: String) {
        synchronized(seen) {
            seen.remove(key)
        }
    }

    private fun shouldAnnounce(captured: CapturedNotice, previous: String?): Boolean {
        if (!settings.speechEnabled || captured.suspended || captured.groupSuppressed) {
            return false
        }
        if (!captured.audible && !settings.includeSilent) {
            return false
        }
        if (captured.title.isBlank() && captured.body.isBlank()) {
            return false
        }
        if (previous == captured.fingerprint) {
            return false
        }
        if (previous != null && (captured.onlyAlertOnce || captured.inProgress)) {
            return false
        }
        if (captured.speech.isBlank()) {
            return true
        }
        return !markDuplicateSpeech(captured.packageName, captured.speech, captured.postedAt)
    }

    private fun markDuplicateSpeech(packageName: String, speech: String, now: Long): Boolean {
        val token = "$packageName\n$speech"
        if (token == recentSpeech && now - recentSpeechAt < DuplicateSpeechWindowMillis) {
            return true
        }
        recentSpeech = token
        recentSpeechAt = now
        return false
    }

    private suspend fun publish(captured: CapturedNotice) {
        val (icon, duration) = coroutineScope {
            val iconTask = async(Dispatchers.IO) { loadIcon(captured.packageName) }
            val durationTask = async(Dispatchers.IO) {
                captured.soundUri?.let { soundDurationMillis(it) }
            }
            iconTask.await() to durationTask.await()
        }
        val wait = if (captured.soundUri == null) {
            0L
        } else {
            (duration ?: FallbackSoundMillis).coerceIn(1L, MaxSoundMillis) + SoundCushionMillis
        }
        notifications.post(
            category = NotificationCategoryDevice,
            title = captured.title.ifBlank { captured.appName },
            body = displayBody(captured.title, captured.body),
            speechText = captured.speech,
            soundEffect = if (captured.audible) {
                NotificationSoundEffect.None
            } else {
                NotificationSoundEffect.Notice
            },
            skipSpeech = captured.speech.isBlank(),
            appIcon = icon,
            sourceLabel = captured.appName,
            speakNotBeforeElapsed = captured.postedAt + wait,
        )
    }

    private fun capture(sbn: StatusBarNotification, ranking: Ranking?): CapturedNotice? {
        if (sbn.packageName == packageName) {
            return null
        }
        val notification = sbn.notification ?: return null
        if (isMediaControl(notification)) {
            return null
        }
        val (title, body) = titleAndBody(notification)
        if (shouldIgnore(sbn.packageName, title, body)) {
            return null
        }
        val appName = appLabel(sbn.packageName)
        val speech = speechText(appName, title, body)
        val audible = isAudible(notification, ranking)
        val suspended = ranking != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            ranking.isSuspended
        return CapturedNotice(
            key = sbn.key,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            body = body,
            speech = speech,
            fingerprint = fingerprint(title, body),
            onlyAlertOnce = notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            inProgress = inProgress(notification),
            groupSuppressed = groupSuppressed(notification),
            suspended = suspended,
            audible = audible,
            soundUri = if (audible) ranking?.channel?.sound else null,
            postedAt = SystemClock.elapsedRealtime(),
        )
    }

    private fun fingerprintOf(sbn: StatusBarNotification): String? {
        if (sbn.packageName == packageName) {
            return null
        }
        val notification = sbn.notification ?: return null
        if (isMediaControl(notification)) {
            return null
        }
        val (title, body) = titleAndBody(notification)
        if (shouldIgnore(sbn.packageName, title, body)) {
            return null
        }
        return fingerprint(title, body)
    }

    private fun fingerprint(title: String, body: String): String = "$title\n$body"

    private fun shouldIgnore(packageName: String, title: String, body: String): Boolean {
        if (title in ignoredNoiseTitles) {
            return true
        }
        return isChromeSiteIndicator(packageName, title, body)
    }

    private fun isChromeSiteIndicator(packageName: String, title: String, body: String): Boolean {
        if (!isChromePackage(packageName)) {
            return false
        }
        return chromeSiteIndicator.containsMatchIn(title) || chromeSiteIndicator.containsMatchIn(body)
    }

    private fun isChromePackage(packageName: String): Boolean {
        return packageName == ChromePackage ||
            packageName == ChromeLegacyPackage ||
            packageName == ChromiumPackage ||
            packageName.startsWith(ChromeChannelPrefix)
    }

    private fun isMediaControl(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) {
            return true
        }
        val extras = notification.extras ?: return false
        if (extras.mediaSessionToken() != null) {
            return true
        }
        return extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle")
    }

    private fun isAudible(notification: Notification, ranking: Ranking?): Boolean {
        if (ranking == null) {
            return false
        }
        if (notification.flags and (FlagSilent or FlagSilentSystem) != 0) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ranking.isSuspended) {
            return false
        }
        if (!ranking.matchesInterruptionFilter()) {
            return false
        }
        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            return false
        }
        val sound = ranking.channel?.sound ?: return false
        return sound != Uri.EMPTY
    }

    private fun groupSuppressed(notification: Notification): Boolean {
        if (notification.group.isNullOrEmpty()) {
            return false
        }
        val summary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        return when (notification.groupAlertBehavior) {
            Notification.GROUP_ALERT_SUMMARY -> !summary
            Notification.GROUP_ALERT_CHILDREN -> summary
            else -> false
        }
    }

    private fun inProgress(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
            return true
        }
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0) {
            return false
        }
        return extras.getInt(Notification.EXTRA_PROGRESS, 0) < max
    }

    private fun titleAndBody(notification: Notification): Pair<String, String> {
        val extras = notification.extras ?: return "" to ""
        val title = extras.textOf(Notification.EXTRA_TITLE).take(BodyLimit)
        val body = lastMessageText(extras).ifBlank {
            inboxText(extras)
        }.ifBlank {
            extras.textOf(Notification.EXTRA_BIG_TEXT)
        }.ifBlank {
            extras.textOf(Notification.EXTRA_TEXT)
        }.ifBlank {
            extras.textOf(Notification.EXTRA_SUB_TEXT)
        }.take(BodyLimit)
        return title to body
    }

    private fun lastMessageText(extras: Bundle): String {
        val bundles = ArrayList<Bundle>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
                ?.let { bundles.addAll(it) }
        } else {
            @Suppress("DEPRECATION")
            val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return ""
            for (item in raw) {
                (item as? Bundle)?.let { bundles.add(it) }
            }
        }
        for (index in bundles.size - 1 downTo 0) {
            val text = bundles[index].getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                return text
            }
        }
        return ""
    }

    private fun inboxText(extras: Bundle): String {
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return ""
        return lines
            .map { it.toString().trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun speechText(appName: String, title: String, body: String): String {
        val spokenApp = normalizeSpeech(appName).ifBlank { "アプリ" }
        val normalizedTitle = normalizeSpeech(title)
        val normalizedBody = normalizeSpeech(body)
        val heading = normalizedTitle.takeUnless { it.equals(spokenApp, ignoreCase = true) }.orEmpty()
        val content = when {
            normalizedBody.isEmpty() -> ""
            normalizedBody.equals(heading, ignoreCase = true) -> ""
            normalizedBody.equals(normalizedTitle, ignoreCase = true) -> ""
            else -> normalizedBody
        }
        val spoken = when {
            heading.isNotEmpty() && content.isNotEmpty() -> "${ensurePeriod(heading)}${ensurePeriod(content)}"
            heading.isNotEmpty() -> ensurePeriod(heading)
            content.isNotEmpty() -> content
            else -> ""
        }
        if (spoken.isBlank()) {
            return ""
        }
        val limited = if (spoken.length <= SpeechContentLimit) {
            spoken
        } else {
            spoken.take(SpeechContentLimit).trimEnd('、', ' ', '。') + "、ほか"
        }
        return "${spokenApp}からの通知。$limited"
    }

    private fun normalizeSpeech(text: String): String {
        return stripEmoji(text)
            .replace("\u200b", "")
            .replace(speechUrl, "")
            .let(::replaceSpeechSymbols)
            .replace(Regex("[（(]\\s*[)）]"), "")
            .replace(Regex("[\\r\\n]+"), "、")
            .replace(Regex("[ \\t\\u3000]+"), " ")
            .replace(Regex("、{2,}"), "、")
            .trim('、', ' ')
    }

    private fun ensurePeriod(text: String): String {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) {
            return ""
        }
        val last = trimmed.last()
        return if (last == '。' || last == '！' || last == '？') {
            trimmed
        } else {
            "$trimmed。"
        }
    }

    private fun displayBody(title: String, body: String): String {
        val text = body.trim()
        if (text.isEmpty() || text == title.trim()) {
            return ""
        }
        return text
    }

    private fun appLabel(packageName: String): String {
        synchronized(labelCache) {
            labelCache[packageName]?.let { return it }
        }
        val label = runCatching {
            val pm = appContext.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "アプリ"
        synchronized(labelCache) {
            labelCache[packageName] = label
        }
        return label
    }

    private fun loadIcon(packageName: String): Bitmap? {
        synchronized(iconCache) {
            if (iconCache.containsKey(packageName)) {
                return iconCache[packageName]
            }
        }
        val bitmap = runCatching {
            appContext.packageManager.getApplicationIcon(packageName).toBitmap(width = 256, height = 256)
        }.getOrNull()
        synchronized(iconCache) {
            iconCache[packageName] = bitmap
        }
        return bitmap
    }

    private fun soundDurationMillis(uri: Uri): Long? {
        val key = uri.toString()
        synchronized(durationCache) {
            durationCache[key]?.let { return it }
        }
        val measured = readDuration(uri) ?: return null
        synchronized(durationCache) {
            durationCache[key] = measured
        }
        return measured
    }

    private fun readDuration(uri: Uri): Long? {
        return retrieverDuration(uri)
    }

    private fun retrieverDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class CapturedNotice(
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val body: String,
        val speech: String,
        val fingerprint: String,
        val onlyAlertOnce: Boolean,
        val inProgress: Boolean,
        val groupSuppressed: Boolean,
        val suspended: Boolean,
        val audible: Boolean,
        val soundUri: Uri?,
        val postedAt: Long,
    )
}

@Composable
fun rememberDeviceNotificationAnnouncer(
    notifications: NotificationCenter,
    settings: NotificationAnnounceSettings,
): DeviceNotificationAnnouncer {
    val context = LocalContext.current
    val announcer = remember(notifications, settings) {
        DeviceNotificationAnnouncer(
            context = context.applicationContext,
            notifications = notifications,
            settings = settings,
        )
    }
    DisposableEffect(announcer) {
        announcer.start()
        onDispose { announcer.stop() }
    }
    return announcer
}

private fun replaceSpeechSymbols(text: String): String {
    if (text.isEmpty()) {
        return text
    }
    val kept = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint in speechSymbols) {
            kept.append(' ')
        } else {
            kept.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
    return kept.toString()
}

private val speechSymbols = setOf(
    '`', '*', '#', '_', '\\', '|', '^', '=', '+', '<', '>',
    '[', ']', '{', '}', '&', '\'', '"', '~',
    '＊', '＃', '＿', '｀', '＾', '｜', '＆', '＋', '＝',
    '＜', '＞', '［', '］', '｛', '｝',
    '•', '·',
).map { it.code }.toSet()

private fun stripEmoji(text: String): String {
    if (text.isEmpty()) {
        return text
    }
    val kept = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (!isEmoji(codePoint)) {
            kept.appendCodePoint(codePoint)
        }
        index += Character.charCount(codePoint)
    }
    return kept.toString()
}

private fun isEmoji(codePoint: Int): Boolean {
    if (codePoint == 0x200D || codePoint == 0xFE0E || codePoint == 0xFE0F || codePoint == 0x20E3) {
        return true
    }
    if (codePoint in 0x2600..0x27BF || codePoint in 0x1F000..0x1FFFF || codePoint in 0xE0020..0xE007F) {
        return true
    }
    return codePoint in extraEmoji
}

private val extraEmoji = intArrayOf(
    0x00A9, 0x00AE,
    0x203C, 0x2049,
    0x2122, 0x2139,
    0x2194, 0x2195, 0x2196, 0x2197, 0x2198, 0x2199,
    0x21A9, 0x21AA,
    0x231A, 0x231B, 0x2328, 0x23CF,
    0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23ED, 0x23EE, 0x23EF,
    0x23F0, 0x23F1, 0x23F2, 0x23F3,
    0x23F8, 0x23F9, 0x23FA,
    0x24C2,
    0x25AA, 0x25AB, 0x25B6, 0x25C0,
    0x25FB, 0x25FC, 0x25FD, 0x25FE,
    0x2934, 0x2935,
    0x2B05, 0x2B06, 0x2B07,
    0x2B1B, 0x2B1C, 0x2B50, 0x2B55,
    0x3030, 0x303D,
    0x3297, 0x3299,
).toSet()

private fun Bundle.textOf(key: String): String {
    return getCharSequence(key)?.toString()?.trim().orEmpty()
}

private fun Bundle.mediaSessionToken(): MediaSession.Token? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(Notification.EXTRA_MEDIA_SESSION)
    }
}

private const val ChromePackage = "com.android.chrome"
private const val ChromeLegacyPackage = "com.google.android.apps.chrome"
private const val ChromiumPackage = "org.chromium.chrome"
private const val ChromeChannelPrefix = "com.chrome."
private val chromeSiteIndicator = Regex("""サイトで.+が(?:使用|共有)されています""")
private val ignoredNoiseTitles = setOf(
    "Spotifyの接続",
    "同期を維持します。",
)

private const val FlagSilent = 0x200
private const val FlagSilentSystem = 1 shl 17
private val speechUrl = Regex(
    """(?i)(?:https?://|www\.)[^\s　、。，！!？?）)」』】>\]]+""",
)

private const val SpeechContentLimit = 150
private const val BodyLimit = 2_000
private const val FallbackSoundMillis = 3_500L
private const val SoundCushionMillis = 350L
private const val MaxSoundMillis = 15_000L
private const val DuplicateSpeechWindowMillis = 4_000L
