package app.smartdisplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import app.smartdisplay.media.SystemMediaHub
import app.smartdisplay.night.canWriteSystemBrightness
import app.smartdisplay.night.noteBrightnessWritePrompted
import app.smartdisplay.night.systemBrightnessSettingsIntent
import app.smartdisplay.steps.StepsAccess

private enum class StartupPermissionStep {
    Runtime,
    Health,
    Brightness,
    Notifications,
    Done,
}

/**
 * 初回起動で、使用許可が要るものを続けて出す。
 * カメラ、カレンダー、歩数、古い端末の画像保存、明るさの変更、通知へのアクセス、
 * Bluetoothの機器名、Wi-Fiのアクセスポイント名。
 */
@Composable
fun StartupPermissionRequests(systemMedia: SystemMediaHub) {
    val context = LocalContext.current
    val prefs = remember {
        context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    }
    var step by remember {
        mutableStateOf(readStep(prefs.getString(KeyStep, null)))
    }
    var linkPromptDone by remember {
        mutableStateOf(prefs.getBoolean(KeyLinkPrompted, false))
    }
    fun markLinkPrompted() {
        prefs.edit().putBoolean(KeyLinkPrompted, true).apply()
        linkPromptDone = true
    }
    remember {
        noteBrightnessWritePrompted(context)
        true
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        when (step) {
            StartupPermissionStep.Brightness -> moveTo(prefs, StartupPermissionStep.Notifications) { step = it }
            StartupPermissionStep.Notifications -> moveTo(prefs, StartupPermissionStep.Done) { step = it }
            StartupPermissionStep.Runtime,
            StartupPermissionStep.Health,
            StartupPermissionStep.Done,
            -> Unit
        }
    }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        markLinkPrompted()
        moveTo(prefs, StartupPermissionStep.Health) { step = it }
    }
    val linkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        markLinkPrompted()
    }
    val healthLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        prefs.edit().putBoolean(KeyHealthPrompted, true).apply()
        if (step == StartupPermissionStep.Health) {
            moveTo(prefs, StartupPermissionStep.Brightness) { step = it }
        }
    }

    LaunchedEffect(step, linkPromptDone) {
        when (step) {
            StartupPermissionStep.Runtime -> {
                val missing = missingRuntimePermissions(context)
                if (missing.isEmpty()) {
                    markLinkPrompted()
                    moveTo(prefs, StartupPermissionStep.Health) { step = it }
                } else {
                    runCatching { runtimeLauncher.launch(missing) }
                        .onFailure {
                            markLinkPrompted()
                            moveTo(prefs, StartupPermissionStep.Health) { step = it }
                        }
                }
            }
            StartupPermissionStep.Health -> {
                if (!StepsAccess.isSdkAvailable(context)) {
                    moveTo(prefs, StartupPermissionStep.Brightness) { step = it }
                } else if (StepsAccess.hasPermission(context)) {
                    prefs.edit().putBoolean(KeyHealthPrompted, true).apply()
                    moveTo(prefs, StartupPermissionStep.Brightness) { step = it }
                } else {
                    runCatching {
                        healthLauncher.launch(StepsAccess.permissionRequest())
                    }.onFailure {
                        prefs.edit().putBoolean(KeyHealthPrompted, true).apply()
                        moveTo(prefs, StartupPermissionStep.Brightness) { step = it }
                    }
                }
            }
            StartupPermissionStep.Brightness -> {
                if (canWriteSystemBrightness(context)) {
                    moveTo(prefs, StartupPermissionStep.Notifications) { step = it }
                } else {
                    runCatching {
                        settingsLauncher.launch(systemBrightnessSettingsIntent(context))
                    }.onFailure {
                        moveTo(prefs, StartupPermissionStep.Notifications) { step = it }
                    }
                }
            }
            StartupPermissionStep.Notifications -> {
                if (systemMedia.hasNotificationAccess()) {
                    moveTo(prefs, StartupPermissionStep.Done) { step = it }
                } else {
                    runCatching {
                        settingsLauncher.launch(systemMedia.notificationAccessIntent())
                    }.onFailure {
                        moveTo(prefs, StartupPermissionStep.Done) { step = it }
                    }
                }
            }
            StartupPermissionStep.Done -> {
                if (!linkPromptDone) {
                    val missing = missingLinkPermissions(context)
                    if (missing.isNotEmpty()) {
                        runCatching { linkLauncher.launch(missing.toTypedArray()) }
                            .onFailure { markLinkPrompted() }
                        return@LaunchedEffect
                    }
                    markLinkPrompted()
                    return@LaunchedEffect
                }
                if (prefs.getBoolean(KeyHealthPrompted, false)) {
                    return@LaunchedEffect
                }
                if (!StepsAccess.isSdkAvailable(context)) {
                    return@LaunchedEffect
                }
                if (StepsAccess.hasPermission(context)) {
                    prefs.edit().putBoolean(KeyHealthPrompted, true).apply()
                    return@LaunchedEffect
                }
                runCatching {
                    healthLauncher.launch(StepsAccess.permissionRequest())
                }.onFailure {
                    prefs.edit().putBoolean(KeyHealthPrompted, true).apply()
                }
            }
        }
    }
}

private fun moveTo(
    prefs: android.content.SharedPreferences,
    next: StartupPermissionStep,
    apply: (StartupPermissionStep) -> Unit,
) {
    prefs.edit().putString(KeyStep, next.name).apply()
    apply(next)
}

private fun readStep(raw: String?): StartupPermissionStep {
    return StartupPermissionStep.entries.firstOrNull { it.name == raw }
        ?: StartupPermissionStep.Runtime
}

private fun missingRuntimePermissions(context: Context): Array<String> {
    val names = mutableListOf<String>()
    if (!isGranted(context, Manifest.permission.CAMERA)) {
        names += Manifest.permission.CAMERA
    }
    if (!isGranted(context, Manifest.permission.READ_CALENDAR)) {
        names += Manifest.permission.READ_CALENDAR
    }
    if (
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        !isGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    ) {
        names += Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
    names += missingLinkPermissions(context)
    return names.toTypedArray()
}

/**
 * Bluetoothの機器名と、Wi-Fiのアクセスポイント名を読む許可。
 * すでに起動案内が終わっている端末にも、一度だけ出す。
 */
private fun missingLinkPermissions(context: Context): List<String> {
    val names = mutableListOf<String>()
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
    ) {
        names += Manifest.permission.BLUETOOTH_CONNECT
    }
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !isGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    ) {
        names += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    if (
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
        !isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    ) {
        names += Manifest.permission.ACCESS_FINE_LOCATION
    }
    return names
}

private fun isGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private const val PrefsName = "startup_permissions"
private const val KeyStep = "step"
private const val KeyHealthPrompted = "health_prompted"
private const val KeyLinkPrompted = "link_prompted"
