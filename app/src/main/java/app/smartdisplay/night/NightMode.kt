package app.smartdisplay.night

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDateTime
import kotlin.math.roundToInt

const val NightModeEndHour = 6
const val NightModeMinBrightness = 0.01f
const val NightModeMaxBrightness = 0.40f
const val NightModeDefaultBrightness = 0.15f
const val NightModeDefaultChimeVolume = 0.4f

/** 0:00 以上、6:00 未満。 */
fun isNightModeHours(now: LocalDateTime): Boolean = now.hour < NightModeEndHour

class NightModeSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean(KeyEnabled, true))
        private set

    var brightness by mutableFloatStateOf(
        prefs.getFloat(KeyBrightness, NightModeDefaultBrightness)
            .coerceIn(NightModeMinBrightness, NightModeMaxBrightness),
    )
        private set

    /** 通常の時報の大きさを 1 とした、ナイトモード中の時報音量。 */
    var chimeVolumeRatio by mutableFloatStateOf(
        prefs.getFloat(KeyChimeVolume, NightModeDefaultChimeVolume).coerceIn(0f, 1f),
    )
        private set

    /** 明るさスライダーを動かしているあいだだけ使う割合。保存しない。 */
    private var brightnessPreviewState by mutableStateOf<Float?>(null)
    val brightnessPreview: Float?
        get() = brightnessPreviewState

    fun updateEnabled(value: Boolean) {
        if (enabled == value) {
            return
        }
        enabled = value
        prefs.edit().putBoolean(KeyEnabled, value).apply()
    }

    fun updateBrightness(value: Float) {
        val next = value.coerceIn(NightModeMinBrightness, NightModeMaxBrightness)
        if (brightness == next) {
            return
        }
        brightness = next
        prefs.edit().putFloat(KeyBrightness, next).apply()
    }

    fun updateChimeVolumeRatio(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (chimeVolumeRatio == next) {
            return
        }
        chimeVolumeRatio = next
        prefs.edit().putFloat(KeyChimeVolume, next).apply()
    }

    fun setBrightnessPreview(value: Float?) {
        brightnessPreviewState = value?.coerceIn(NightModeMinBrightness, NightModeMaxBrightness)
    }

    private companion object {
        const val PrefsName = "night_mode"
        const val KeyEnabled = "enabled"
        const val KeyBrightness = "brightness"
        const val KeyChimeVolume = "chime_volume_ratio"
    }
}

@Composable
fun rememberNightModeSettings(): NightModeSettings {
    val context = LocalContext.current
    return remember { NightModeSettings(context.applicationContext) }
}

fun canWriteSystemBrightness(context: Context): Boolean {
    return Settings.System.canWrite(context)
}

/** 起動時に明るさの変更許可を案内したあとは、ナイトモード開始時に重ねて出さない。 */
fun noteBrightnessWritePrompted(context: Context) {
    context.applicationContext
        .getSharedPreferences("night_mode", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("brightness_write_prompted", true)
        .apply()
}

fun systemBrightnessSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
}

private enum class BrightnessTarget {
    /** ナイトモードの割合。 */
    Night,

    /** 切り替える前の明るさ。自動は戻さない。 */
    Original,

    /** 端末の最大の明るさ。 */
    Maximum,

    /** ナイトモードも緊急全画面も無い。自動設定ごと元に戻す。 */
    System,
}

/**
 * 夜間は明るさの自動を切り、[brightness] の割合にする。
 * 通知中、操作中、メディア再生中は切り替える前の明るさ、緊急の全画面は最大にする。
 * ナイトモードを終えたときだけ、切り替える前の自動設定と明るさに戻す。
 */
@Composable
fun NightModeBrightness(
    enabled: Boolean,
    brightness: Float,
    now: LocalDateTime,
    notificationVisible: Boolean,
    crisisFullScreen: Boolean,
    holdBright: Boolean,
    mediaPlaying: Boolean = false,
    previewRatio: Float? = null,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val control = remember(context) {
        SystemBrightnessOverride(context.applicationContext)
    }
    if (activity == null) {
        return
    }
    val nightActive = enabled && isNightModeHours(now)
    val target = when {
        crisisFullScreen -> BrightnessTarget.Maximum
        nightActive && (notificationVisible || holdBright || mediaPlaying) -> BrightnessTarget.Original
        nightActive -> BrightnessTarget.Night
        else -> BrightnessTarget.System
    }
    val level = brightness.coerceIn(NightModeMinBrightness, NightModeMaxBrightness)
    DisposableEffect(activity, control) {
        onDispose { control.restore(activity.window) }
    }
    SideEffect {
        val preview = previewRatio
        if (preview != null) {
            control.preview(activity.window, preview)
        } else {
            control.sync(
                window = activity.window,
                target = target,
                ratio = level,
            )
        }
        if (nightActive && control.consumeWritePrompt()) {
            context.startActivity(systemBrightnessSettingsIntent(context))
        }
    }
}

/**
 * 端末の明るさ設定を一時的に手動へ切り替え、終了時に元へ戻す。
 * 取得した元の値はプロセスが落ちても戻せるよう保存する。
 */
private class SystemBrightnessOverride(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    /** 明るさの変更許可が無いとき、ナイトモード開始時に一度だけ案内する。 */
    fun consumeWritePrompt(): Boolean {
        if (canWriteSystemBrightness(appContext) || prefs.getBoolean(KeyPrompted, false)) {
            return false
        }
        prefs.edit().putBoolean(KeyPrompted, true).apply()
        return true
    }

    /** スライダー確認用。終わったら [sync] が元の対象へ戻す。 */
    fun preview(window: Window, ratio: Float) {
        val level = ratio.coerceIn(0f, 1f)
        if (!canWriteSystemBrightness(appContext)) {
            setWindowBrightness(window, level)
            return
        }
        captureIfNeeded()
        setWindowBrightness(window, level)
        writeManual(systemLevel(level, brightnessRange()))
    }

    fun sync(
        window: Window,
        target: BrightnessTarget,
        ratio: Float,
    ) {
        if (!canWriteSystemBrightness(appContext)) {
            val windowLevel = when (target) {
                BrightnessTarget.Night -> ratio
                BrightnessTarget.Maximum -> 1f
                BrightnessTarget.Original,
                BrightnessTarget.System,
                -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            setWindowBrightness(window, windowLevel)
            return
        }
        if (target == BrightnessTarget.System) {
            restore(window)
            return
        }
        captureIfNeeded()
        val range = brightnessRange()
        when (target) {
            BrightnessTarget.Night -> {
                setWindowBrightness(window, ratio)
                writeManual(systemLevel(ratio, range))
            }
            BrightnessTarget.Original -> {
                val saved = prefs.getInt(KeySavedLevel, systemLevel(ratio, range)).coerceIn(range)
                writeManual(saved)
                setWindowBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            }
            BrightnessTarget.Maximum -> {
                setWindowBrightness(window, 1f)
                writeManual(range.last)
            }
            BrightnessTarget.System -> Unit
        }
    }

    fun restore(window: Window) {
        if (!prefs.getBoolean(KeyActive, false)) {
            setWindowBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            return
        }
        if (!canWriteSystemBrightness(appContext)) {
            setWindowBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            return
        }
        val mode = prefs.getInt(
            KeySavedMode,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        )
        val level = prefs.getInt(KeySavedLevel, -1)
        if (level >= 0) {
            putInt(Settings.System.SCREEN_BRIGHTNESS, level)
        }
        putInt(Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        prefs.edit()
            .remove(KeyActive)
            .remove(KeySavedMode)
            .remove(KeySavedLevel)
            .apply()
        setWindowBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun captureIfNeeded() {
        if (prefs.getBoolean(KeyActive, false)) {
            return
        }
        val mode = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
        )
        val level = Settings.System.getInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS,
            systemLevel(0.5f, brightnessRange()),
        )
        prefs.edit()
            .putBoolean(KeyActive, true)
            .putInt(KeySavedMode, mode)
            .putInt(KeySavedLevel, level)
            .apply()
    }

    /** 明るさを先に合わせてから自動を切る。切った直後にもう一度書いて跳ねを防ぐ。 */
    private fun writeManual(level: Int) {
        val brightnessKey = Settings.System.SCREEN_BRIGHTNESS
        val modeKey = Settings.System.SCREEN_BRIGHTNESS_MODE
        val manual = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        val currentLevel = Settings.System.getInt(resolver, brightnessKey, -1)
        val currentMode = Settings.System.getInt(resolver, modeKey, -1)
        if (currentLevel == level && currentMode == manual) {
            return
        }
        putInt(brightnessKey, level)
        if (currentMode != manual) {
            putInt(modeKey, manual)
            putInt(brightnessKey, level)
        }
    }

    private fun putInt(key: String, value: Int) {
        runCatching {
            Settings.System.putInt(resolver, key, value)
        }
    }

    private fun systemLevel(ratio: Float, range: IntRange): Int {
        val max = range.last
        return (ratio.coerceIn(0f, 1f) * max).roundToInt().coerceIn(range)
    }

    private fun brightnessRange(): IntRange {
        val resources = android.content.res.Resources.getSystem()
        fun integer(name: String, fallback: Int): Int {
            val id = resources.getIdentifier(name, "integer", "android")
            return if (id != 0) resources.getInteger(id) else fallback
        }
        val min = integer("config_screenBrightnessSettingMinimum", 1).coerceAtLeast(1)
        val max = integer("config_screenBrightnessSettingMaximum", 255).coerceAtLeast(min)
        return min..max
    }

    private fun setWindowBrightness(window: Window, value: Float) {
        val params = window.attributes
        if (params.screenBrightness == value) {
            return
        }
        params.screenBrightness = value
        window.attributes = params
    }

    private companion object {
        const val PrefsName = "night_mode"
        const val KeyActive = "brightness_override_active"
        const val KeySavedMode = "saved_brightness_mode"
        const val KeySavedLevel = "saved_brightness_level"
        const val KeyPrompted = "brightness_write_prompted"
    }
}
