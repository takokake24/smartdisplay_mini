package app.smartdisplay.notification

import android.graphics.Bitmap
import java.time.LocalDateTime

enum class NotificationSoundEffect {
    None,
    Notice,
    Beep,
    Battery,
}

data class AppNotification(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val speechText: String,
    val createdAt: LocalDateTime,
    val soundEffect: NotificationSoundEffect = NotificationSoundEffect.Notice,
    val skipSpeech: Boolean = false,
    val appIcon: Bitmap? = null,
    val sourceLabel: String = "",
    /** この時刻（elapsedRealtime）まで、読み上げを始めない。0 なら待たない。 */
    val speakNotBeforeElapsed: Long = 0L,
    /** false のときはバナーを出さず、音声だけ待ち列に載せる。 */
    val showBanner: Boolean = true,
)

const val NotificationCategoryGeneral = "general"
const val NotificationCategoryDevice = "device"
const val ActiveNotificationTimeoutMillis = 60_000L

data class NotificationQueueProgress(
    val current: Int,
    val total: Int,
) {
    val label: String
        get() = "$current/$total"
}
