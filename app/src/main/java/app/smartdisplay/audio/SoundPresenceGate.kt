package app.smartdisplay.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * mini固有: 外出先でスピーカーから音が出ないようにする。
 *
 * 鳴らす条件は次のいずれか。
 * - Wi-Fi接続中
 * - 他アプリのメディア再生中（音を出してよい環境とみなす）
 * - Bluetoothのイヤホン／ヘッドホン／スピーカー接続中
 * - 「外出中も鳴らす」が一時ON
 *
 * 「外出中も鳴らす」はメモリのみ。Wi-Fi・メディア・Bluetooth音声のいずれかが
 * 次に満たされたとき、または [release] 時にOFFへ戻す。
 */
class SoundPresenceGate(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    private var wifiConnected = false
    private var bluetoothAudioConnected = false
    private var externalMediaPlaying = false
    private var naturalAllow = false
    private var started = false

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    /** 通知設定の「外出中も鳴らす」。一時的な上書き。 */
    var awaySoundEnabled by mutableStateOf(false)
        private set

    var mayPlay by mutableStateOf(true)
        private set

    fun mayPlayNow(): Boolean = mayPlay

    fun setAwaySoundEnabled(value: Boolean) {
        if (awaySoundEnabled == value) {
            return
        }
        awaySoundEnabled = value
        recompute()
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        startWifi()
        startBluetoothAudio()
        startExternalMedia()
        recompute()
    }

    fun release() {
        if (!started) {
            awaySoundEnabled = false
            return
        }
        started = false
        awaySoundEnabled = false
        wifiCallback?.let { callback ->
            connectivityManager?.runCatching { unregisterNetworkCallback(callback) }
        }
        wifiCallback = null
        deviceCallback?.let { callback ->
            audioManager?.runCatching { unregisterAudioDeviceCallback(callback) }
        }
        deviceCallback = null
        playbackCallback?.let { callback ->
            audioManager?.runCatching { unregisterAudioPlaybackCallback(callback) }
        }
        playbackCallback = null
        recompute()
    }

    private fun startWifi() {
        val manager = connectivityManager ?: return
        wifiConnected = anyWifiConnected(manager)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiConnected = true
                recompute()
            }

            override fun onLost(network: Network) {
                wifiConnected = anyWifiConnected(manager)
                recompute()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                wifiConnected = anyWifiConnected(manager)
                recompute()
            }
        }
        wifiCallback = callback
        runCatching { manager.registerNetworkCallback(request, callback, handler) }
            .onFailure {
                runCatching { manager.registerNetworkCallback(request, callback) }
            }
    }

    private fun startBluetoothAudio() {
        val manager = audioManager ?: return
        bluetoothAudioConnected = hasBluetoothAudioOutput(manager)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                bluetoothAudioConnected = hasBluetoothAudioOutput(manager)
                recompute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                bluetoothAudioConnected = hasBluetoothAudioOutput(manager)
                recompute()
            }
        }
        deviceCallback = callback
        manager.registerAudioDeviceCallback(callback, handler)
    }

    private fun startExternalMedia() {
        val manager = audioManager ?: return
        externalMediaPlaying = isExternalMediaPlaying(manager)
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                externalMediaPlaying = isExternalMediaPlaying(manager)
                recompute()
            }
        }
        playbackCallback = callback
        manager.registerAudioPlaybackCallback(callback, handler)
    }

    private fun recompute() {
        if (Looper.myLooper() != handler.looper) {
            handler.post { recompute() }
            return
        }
        val nextNatural = wifiConnected || externalMediaPlaying || bluetoothAudioConnected
        if (awaySoundEnabled && nextNatural && !naturalAllow) {
            awaySoundEnabled = false
        }
        naturalAllow = nextNatural
        mayPlay = nextNatural || awaySoundEnabled
    }

    private companion object {
        fun anyWifiConnected(manager: ConnectivityManager): Boolean {
            return manager.allNetworks.any { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }

        fun hasBluetoothAudioOutput(manager: AudioManager): Boolean {
            return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                isBluetoothAudioType(device.type)
            }
        }

        fun isBluetoothAudioType(type: Int): Boolean {
            return when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                -> true
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                    ) {
                        true
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    ) {
                        true
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        type == AudioDeviceInfo.TYPE_HEARING_AID
                    ) {
                        true
                    } else {
                        false
                    }
                }
            }
        }

        fun isExternalMediaPlaying(manager: AudioManager): Boolean {
            val myUid = Process.myUid()
            return manager.activePlaybackConfigurations.any { config ->
                if (config.clientUid == myUid) {
                    return@any false
                }
                when (config.audioAttributes.usage) {
                    AudioAttributes.USAGE_MEDIA,
                    AudioAttributes.USAGE_GAME,
                    -> true
                    else -> false
                }
            }
        }
    }
}

@Composable
fun rememberSoundPresenceGate(): SoundPresenceGate {
    val context = LocalContext.current
    val gate = remember { SoundPresenceGate(context.applicationContext) }
    DisposableEffect(gate) {
        gate.start()
        onDispose { gate.release() }
    }
    return gate
}
