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
import app.smartdisplay.media.SystemMediaHub

private enum class StartupPermissionStep {
    Runtime,
    Notifications,
    Done,
}

/**
 * 初回起動で、使用許可が要るものを続けて出す。
 * カレンダー、通知へのアクセス、Bluetoothの機器名、Wi-Fiのアクセスポイント名。
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
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (step == StartupPermissionStep.Notifications) {
            moveTo(prefs, StartupPermissionStep.Done) { step = it }
        }
    }
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        markLinkPrompted()
        moveTo(prefs, StartupPermissionStep.Notifications) { step = it }
    }
    val linkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        markLinkPrompted()
    }

    LaunchedEffect(step, linkPromptDone) {
        when (step) {
            StartupPermissionStep.Runtime -> {
                val missing = missingRuntimePermissions(context)
                if (missing.isEmpty()) {
                    markLinkPrompted()
                    moveTo(prefs, StartupPermissionStep.Notifications) { step = it }
                } else {
                    runCatching { runtimeLauncher.launch(missing) }
                        .onFailure {
                            markLinkPrompted()
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
                if (linkPromptDone) {
                    return@LaunchedEffect
                }
                val missing = missingLinkPermissions(context)
                if (missing.isNotEmpty()) {
                    runCatching { linkLauncher.launch(missing.toTypedArray()) }
                        .onFailure { markLinkPrompted() }
                    return@LaunchedEffect
                }
                markLinkPrompted()
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
    if (!isGranted(context, Manifest.permission.READ_CALENDAR)) {
        names += Manifest.permission.READ_CALENDAR
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
    // WifiInfo の SSID は getScanResults と同じく位置情報が要る（Android 13 以降も）。
    if (!isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
        names += Manifest.permission.ACCESS_FINE_LOCATION
    }
    return names
}

private fun isGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private const val PrefsName = "startup_permissions"
private const val KeyStep = "step"
private const val KeyLinkPrompted = "link_prompted_v2"
