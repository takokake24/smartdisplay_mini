package app.smartdisplay.notification

import android.graphics.Bitmap
import app.smartdisplay.weather.WeatherSnapshot
import java.time.LocalDateTime

enum class NotificationStatusMark {
    Normal,
    Caution,
    Stopped,
}

enum class NotificationSoundEffect {
    None,
    Notice,
    Alert1,
    Beep,
    Sunset,
}

data class CalendarNotificationItem(
    val timeLabel: String,
    val title: String,
    val allDay: Boolean,
)

data class ExpirationNotificationItem(
    val name: String,
    val quantity: Int,
    val dateLabel: String,
    val remainingDays: Int,
)

data class AppNotification(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val speechText: String,
    val createdAt: LocalDateTime,
    val statusMark: NotificationStatusMark? = null,
    val iconResIds: List<Int> = emptyList(),
    val calendarItems: List<CalendarNotificationItem> = emptyList(),
    val expirationItems: List<ExpirationNotificationItem> = emptyList(),
    val memorialNames: List<String> = emptyList(),
    val quoteAuthor: String = "",
    val eventCount: Int? = null,
    val weatherSnapshot: WeatherSnapshot? = null,
    val stepsToday: Long? = null,
    val stepsWeek: Long? = null,
    val earthquakeIntensityScale: Int? = null,
    val earthquakeOccurredAt: LocalDateTime? = null,
    val soundEffect: NotificationSoundEffect = NotificationSoundEffect.Notice,
    val priority: Boolean = false,
    val skipSpeech: Boolean = false,
    val appIcon: Bitmap? = null,
    val sourceLabel: String = "",
    /** この時刻（elapsedRealtime）まで、読み上げを始めない。0 なら待たない。 */
    val speakNotBeforeElapsed: Long = 0L,
    val read: Boolean = false,
    /** 期限では消さない。ユーザーが履歴から消したときだけ消える。 */
    val retained: Boolean = false,
    /** 閉じるまでバナーを出したままにする。読み上げには進まない。 */
    val holdUntilDismiss: Boolean = false,
) {
    val categoryLabel: String
        get() = sourceLabel.ifBlank { categoryLabelOf(category) }

    val isCalendar: Boolean
        get() = category == NotificationCategoryCalendar || category == NotificationCategoryCalendarSoon

    val isCalendarSoon: Boolean
        get() = category == NotificationCategoryCalendarSoon

    val isMemorial: Boolean
        get() = category == NotificationCategoryMemorial

    val isQuote: Boolean
        get() = category == NotificationCategoryQuote

    val isWeather: Boolean
        get() = category == NotificationCategoryWeather

    val isSteps: Boolean
        get() = category == NotificationCategorySteps

    val isTransit: Boolean
        get() = category == NotificationCategoryTransit

    val isGarbage: Boolean
        get() = category == NotificationCategoryGarbage

    val isExpiration: Boolean
        get() = category == NotificationCategoryExpiration

    val isBus: Boolean
        get() = category == NotificationCategoryBus

    val isEew: Boolean
        get() = category == NotificationCategoryEew

    val isEarthquakeIntensity: Boolean
        get() = category == NotificationCategoryEarthquakeIntensity

    val isWeatherWarning: Boolean
        get() = category == NotificationCategoryWeatherWarning

    val isWeatherWarningCritical: Boolean
        get() = category == NotificationCategoryWeatherWarningCritical

    /** 緊急地震速報・震度・気象警報。サウンドテスト中も止めたままにしない。 */
    val isEmergencyNotification: Boolean
        get() = isEew || isEarthquakeIntensity || isWeatherWarning || isWeatherWarningCritical

    /** 緊急排他中のハーフバナー（EEW・気象特別／危険警報）。 */
    val isCrisisExclusiveBanner: Boolean
        get() = isEew || isWeatherWarningCritical
}

const val NotificationCategoryTransit = "transit"
const val NotificationCategoryBus = "bus"
const val NotificationCategoryCalendar = "calendar"
const val NotificationCategoryCalendarSoon = "calendar_soon"
const val NotificationCategoryMemorial = "memorial"
const val NotificationCategoryQuote = "quote"
const val NotificationCategoryWeather = "weather"
const val NotificationCategorySteps = "steps"
const val NotificationCategoryWeatherWarning = "weather_warning"
const val NotificationCategoryWeatherWarningCritical = "weather_warning_critical"
const val NotificationCategoryEew = "eew"
const val NotificationCategoryEarthquakeIntensity = "earthquake_intensity"
const val NotificationCategoryGeneral = "general"
const val NotificationCategoryDevice = "device"
const val NotificationCategorySunset = "sunset"
const val NotificationCategoryGarbage = "garbage"
const val NotificationCategoryExpiration = "expiration"
const val NotificationCategoryAwayAlert = "away_alert"
const val NotificationCategoryTimer = "timer"
const val NotificationCategoryAlarm = "alarm"
const val ActiveNotificationTimeoutMillis = 60_000L
const val NotificationHistoryRetentionHours = 12L
const val NotificationHistoryPruneIntervalMillis = 60_000L

data class NotificationQueueProgress(
    val current: Int,
    val total: Int,
) {
    val label: String
        get() = "$current/$total"
}

fun supportsMuteToday(category: String): Boolean {
    return category == NotificationCategoryTransit ||
        category == NotificationCategoryGarbage ||
        category == NotificationCategoryCalendarSoon
}

fun categoryLabelOf(category: String): String {
    return when (category) {
        NotificationCategoryTransit -> "運行情報"
        NotificationCategoryBus -> "バス時刻表"
        NotificationCategoryCalendar,
        NotificationCategoryCalendarSoon,
        -> "カレンダー"
        NotificationCategoryMemorial -> "記念日"
        NotificationCategoryQuote -> "格言"
        NotificationCategoryWeather -> "天気予報"
        NotificationCategorySteps -> "歩数計"
        NotificationCategoryWeatherWarning,
        NotificationCategoryWeatherWarningCritical,
        -> "気象警報"
        NotificationCategoryEew -> "緊急地震速報"
        NotificationCategoryEarthquakeIntensity -> "地震情報"
        NotificationCategoryGeneral -> "お知らせ"
        NotificationCategoryDevice -> "アプリ"
        NotificationCategorySunset -> "日の入り"
        NotificationCategoryGarbage -> "ごみ出し"
        NotificationCategoryExpiration -> "賞味期限管理"
        NotificationCategoryAwayAlert -> "外出アラート"
        NotificationCategoryTimer -> "タイマー"
        NotificationCategoryAlarm -> "アラーム"
        else -> category
    }
}
