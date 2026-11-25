package app.smartdisplay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.smartdisplay.util.showOsAlert

private const val UnknownEndpoint = "不明"
private const val WifiNameGraceMs = 350L
private const val WifiBaselineMs = 400L

/**
 * Bluetooth機器とWi-Fiの接続・切断を、画面下中央のトーストで知らせる。音は鳴らさない。
 * 監視を始めた時点で既に繋がっているものは出さない。名前が取れないときは「不明」にする。
 */
@Composable
fun rememberLinkConnectionAlerts() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner) {
        val alerts = LinkConnectionAlerts(context.applicationContext)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                alerts.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        alerts.start()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            alerts.stop()
        }
    }
}

@SuppressLint("MissingPermission")
private class LinkConnectionAlerts(
    private val context: Context,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothConnected = linkedMapOf<String, String>()
    private val wifiLinks = linkedMapOf<String, String>()
    private val knownWifi = mutableSetOf<String>()
    private val connectTickets = mutableMapOf<String, Int>()
    private var connectSerial = 0
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiAnnouncementsOpen = false

    fun start() {
        startBluetooth()
        startWifi()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        bluetoothReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        bluetoothReceiver = null
        bluetoothConnected.clear()
        val manager = connectivityManager
        val callback = wifiCallback
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        wifiCallback = null
        connectivityManager = null
        wifiLinks.clear()
        knownWifi.clear()
        connectTickets.clear()
        wifiAnnouncementsOpen = false
    }

    private fun startBluetooth() {
        if (bluetoothReceiver != null || !canReadBluetooth()) {
            return
        }
        seedBluetooth()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = bluetoothDevice(intent) ?: return
                        onBluetoothLink(device, connected = true)
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = bluetoothDevice(intent) ?: return
                        onBluetoothLink(device, connected = false)
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> onBluetoothRadioOff(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (!registered) {
            bluetoothConnected.clear()
            return
        }
        bluetoothReceiver = receiver
    }

    private fun startWifi() {
        val manager = connectivityManager
            ?: context.getSystemService(ConnectivityManager::class.java)
            ?: return
        connectivityManager = manager
        if (wifiCallback != null) {
            refreshWifiNames(manager)
            return
        }
        wifiAnnouncementsOpen = false
        seedWifi(manager)
        val callback = wifiNetworkCallback()
        val registered = runCatching { registerWifiCallback(manager, callback) }.isSuccess
        if (!registered) {
            return
        }
        wifiCallback = callback
        handler.postDelayed({ wifiAnnouncementsOpen = true }, WifiBaselineMs)
    }

    private fun wifiNetworkCallback(): ConnectivityManager.NetworkCallback {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
            ) {
                override fun onAvailable(network: Network) {
                    onWifiUp(network, null)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    onWifiCapabilities(network, networkCapabilities)
                }

                override fun onLost(network: Network) {
                    onWifiDown(network)
                }
            }
        }
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onWifiUp(network, null)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                onWifiCapabilities(network, networkCapabilities)
            }

            override fun onLost(network: Network) {
                onWifiDown(network)
            }
        }
    }

    private fun onWifiCapabilities(network: Network, networkCapabilities: NetworkCapabilities) {
        if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return
        }
        onWifiUp(network, ssidFrom(networkCapabilities))
    }

    private fun registerWifiCallback(
        manager: ConnectivityManager,
        callback: ConnectivityManager.NetworkCallback,
    ) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        manager.registerNetworkCallback(request, callback, handler)
    }

    private fun seedBluetooth() {
        bluetoothConnected.clear()
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        for (profile in bluetoothProfiles()) {
            val devices = runCatching { manager.getConnectedDevices(profile) }.getOrNull() ?: continue
            for (device in devices) {
                val address = deviceAddress(device) ?: continue
                bluetoothConnected[address] = deviceLabel(device)
            }
        }
    }

    private fun onBluetoothLink(device: BluetoothDevice, connected: Boolean) {
        val address = deviceAddress(device) ?: return
        val name = deviceLabel(device)
        if (connected) {
            val previous = bluetoothConnected[address]
            if (previous != null) {
                if (previous == UnknownEndpoint && name != UnknownEndpoint) {
                    bluetoothConnected[address] = name
                }
                return
            }
            bluetoothConnected[address] = name
            showOsAlert(bluetoothText(connected = true, name))
            return
        }
        val previous = bluetoothConnected.remove(address) ?: return
        val label = if (name != UnknownEndpoint) name else previous
        showOsAlert(bluetoothText(connected = false, label))
    }

    private fun onBluetoothRadioOff(intent: Intent) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        if (state != BluetoothAdapter.STATE_OFF || bluetoothConnected.isEmpty()) {
            return
        }
        val names = bluetoothConnected.values.toList()
        bluetoothConnected.clear()
        for (name in names) {
            showOsAlert(bluetoothText(connected = false, name))
        }
    }

    private fun seedWifi(manager: ConnectivityManager) {
        wifiLinks.clear()
        knownWifi.clear()
        val networks = runCatching { manager.allNetworks }.getOrNull() ?: return
        for (network in networks) {
            val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue
            }
            val key = networkKey(network)
            wifiLinks[key] = ssidFrom(caps) ?: UnknownEndpoint
            knownWifi += key
        }
    }

    private fun refreshWifiNames(manager: ConnectivityManager) {
        val networks = runCatching { manager.allNetworks }.getOrNull() ?: return
        for (network in networks) {
            val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue
            }
            val key = networkKey(network)
            if (wifiLinks[key] != UnknownEndpoint) {
                continue
            }
            val resolved = ssidFrom(caps) ?: continue
            wifiLinks[key] = resolved
        }
    }

    private fun onWifiUp(network: Network, name: String?) {
        val key = networkKey(network)
        val wasExisting = knownWifi.remove(key)
        val previous = wifiLinks[key]
        val resolved = name?.takeIf { it.isNotBlank() }
        if (previous == null) {
            wifiLinks[key] = resolved ?: UnknownEndpoint
        } else if (resolved != null) {
            wifiLinks[key] = resolved
        }
        if (!wifiAnnouncementsOpen || wasExisting) {
            return
        }
        if (previous == null) {
            if (resolved != null) {
                showOsAlert(wifiText(connected = true, resolved))
            } else {
                scheduleConnectToast(key)
            }
            return
        }
        if (resolved == null || previous == UnknownEndpoint || resolved == previous) {
            return
        }
        cancelConnectToast(key)
        showOsAlert(wifiText(connected = false, previous))
        showOsAlert(wifiText(connected = true, resolved))
    }

    private fun onWifiDown(network: Network) {
        val key = networkKey(network)
        knownWifi.remove(key)
        cancelConnectToast(key)
        val previous = wifiLinks.remove(key) ?: return
        if (!wifiAnnouncementsOpen) {
            return
        }
        showOsAlert(wifiText(connected = false, previous))
    }

    private fun scheduleConnectToast(key: String) {
        val ticket = ++connectSerial
        connectTickets[key] = ticket
        handler.postDelayed({
            if (connectTickets[key] != ticket) {
                return@postDelayed
            }
            connectTickets.remove(key)
            val name = wifiLinks[key] ?: return@postDelayed
            showOsAlert(wifiText(connected = true, name))
        }, WifiNameGraceMs)
    }

    private fun cancelConnectToast(key: String) {
        connectTickets.remove(key)
    }

    private fun ssidFrom(caps: NetworkCapabilities?): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val info = caps?.transportInfo as? WifiInfo
                cleanSsid(info?.ssid)?.let { return@runCatching it }
            }
            val wifi = context.getSystemService(WifiManager::class.java) ?: return@runCatching null
            @Suppress("DEPRECATION")
            cleanSsid(wifi.connectionInfo?.ssid)
        }.getOrNull()
    }

    private fun canReadBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return isGranted(android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

/** BluetoothProfile.HID_HOST（@SystemApi / hide）。キーボード等の接続済み検知用。 */
private const val BluetoothHidHost = 4

private fun bluetoothProfiles(): List<Int> {
    val profiles = mutableListOf(
        BluetoothProfile.HEADSET,
        BluetoothProfile.A2DP,
        BluetoothProfile.GATT,
        BluetoothProfile.GATT_SERVER,
        BluetoothHidHost,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        profiles += BluetoothProfile.HEARING_AID
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        profiles += BluetoothProfile.LE_AUDIO
    }
    return profiles
}

private fun bluetoothDevice(intent: Intent): BluetoothDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
}

@SuppressLint("MissingPermission")
private fun deviceAddress(device: BluetoothDevice): String? {
    return runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() }
}

@SuppressLint("MissingPermission")
private fun deviceLabel(device: BluetoothDevice): String {
    val label = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            device.alias?.takeIf { it.isNotBlank() } ?: device.name
        } else {
            device.name
        }
    }.getOrNull()
    return label?.takeIf { it.isNotBlank() } ?: UnknownEndpoint
}

private fun cleanSsid(raw: String?): String? {
    if (raw.isNullOrBlank()) {
        return null
    }
    val trimmed = raw.trim()
    if (trimmed == WifiManager.UNKNOWN_SSID || trimmed.equals("<unknown ssid>", ignoreCase = true)) {
        return null
    }
    if (trimmed == "0x") {
        return null
    }
    val unquoted = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        trimmed.substring(1, trimmed.lastIndex)
    } else {
        trimmed
    }
    return unquoted.trim().takeIf { it.isNotEmpty() }
}

private fun networkKey(network: Network): String = network.toString()

private fun bluetoothText(connected: Boolean, name: String): String {
    return if (connected) {
        "Bluetooth機器「$name」に接続しました。"
    } else {
        "Bluetooth機器「$name」を切断しました。"
    }
}

private fun wifiText(connected: Boolean, name: String): String {
    return if (connected) {
        "Wi-Fi「$name」に接続しました。"
    } else {
        "Wi-Fi「$name」を切断しました。"
    }
}
