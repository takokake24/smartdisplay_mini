package app.smartdisplay.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.smartdisplay.media.MediaNotificationListener

/**
 * mini固有: 外出先でスピーカーから音が出ないようにする。
 *
 * 鳴らす条件は次のいずれか。
 * - Wi-Fi接続中
 * - 他アプリのメディア再生中（音を出してよい環境とみなす）
 * - Bluetoothのイヤホン／ヘッドホン／スピーカー接続中（スマートウォッチなどは対象外）
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
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, MediaNotificationListener::class.java)

    private var naturalAllow = false
    private var started = false

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var linkReceiver: BroadcastReceiver? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!started) {
                return
            }
            recompute()
            handler.postDelayed(this, RefreshIntervalMs)
        }
    }

    /** 通知設定の「外出中も鳴らす」。一時的な上書き。 */
    private var awaySoundEnabledState by mutableStateOf(false)
    val awaySoundEnabled: Boolean get() = awaySoundEnabledState

    var mayPlay by mutableStateOf(true)
        private set

    fun mayPlayNow(): Boolean {
        recompute()
        return mayPlay
    }

    fun setAwaySoundEnabled(value: Boolean) {
        if (awaySoundEnabledState == value) {
            return
        }
        awaySoundEnabledState = value
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
        startLinkReceiver()
        recompute()
        handler.postDelayed(refreshRunnable, RefreshIntervalMs)
    }

    fun release() {
        if (!started) {
            awaySoundEnabledState = false
            return
        }
        started = false
        awaySoundEnabledState = false
        handler.removeCallbacks(refreshRunnable)
        wifiCallback?.let { callback ->
            connectivityManager?.runCatching { unregisterNetworkCallback(callback) }
        }
        wifiCallback = null
        linkReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        linkReceiver = null
        deviceCallback?.let { callback ->
            audioManager?.runCatching { unregisterAudioDeviceCallback(callback) }
        }
        deviceCallback = null
        sessionsListener?.let { listener ->
            mediaSessionManager?.runCatching { removeOnActiveSessionsChangedListener(listener) }
        }
        sessionsListener = null
        recompute()
    }

    private fun startWifi() {
        val manager = connectivityManager
        if (manager != null) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = recompute()

                override fun onLost(network: Network) = recompute()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = recompute()
            }
            wifiCallback = callback
            runCatching { manager.registerNetworkCallback(request, callback, handler) }
                .onFailure {
                    runCatching { manager.registerNetworkCallback(request, callback) }
                }
        }
    }

    private fun startLinkReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = recompute()
        }
        linkReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun startBluetoothAudio() {
        val manager = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = recompute()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = recompute()
        }
        deviceCallback = callback
        manager.registerAudioDeviceCallback(callback, handler)
    }

    private fun startExternalMedia() {
        val manager = mediaSessionManager ?: return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { recompute() }
        sessionsListener = listener
        runCatching {
            manager.addOnActiveSessionsChangedListener(listener, listenerComponent, handler)
        }
    }

    private fun recompute() {
        if (Looper.myLooper() != handler.looper) {
            handler.post { recompute() }
            return
        }
        if (!started) {
            mayPlay = false
            naturalAllow = false
            return
        }
        val wifiConnected = wifiConnectedNow()
        val bluetoothAudioConnected = bluetoothAudioConnectedNow()
        val externalMediaPlaying = externalMediaPlayingNow()
        val nextNatural = wifiConnected || externalMediaPlaying || bluetoothAudioConnected
        if (awaySoundEnabledState && nextNatural && !naturalAllow) {
            awaySoundEnabledState = false
        }
        naturalAllow = nextNatural
        mayPlay = nextNatural || awaySoundEnabledState
    }

    private fun wifiConnectedNow(): Boolean {
        val wifi = wifiManager
        if (wifi != null && !wifi.isWifiEnabled) {
            return false
        }
        val manager = connectivityManager ?: return false
        return manager.allNetworks.any { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    private fun bluetoothAudioConnectedNow(): Boolean {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return false
        }
        val audio = audioManager
        if (audio != null) {
            val matched = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { info ->
                isAllowedBluetoothAudioOutput(info, adapter)
            }
            if (matched) {
                return true
            }
        }
        return connectedHeadsetOrSpeakerDevices().any { isHeadphoneOrSpeakerDevice(it) }
    }

    private fun isAllowedBluetoothAudioOutput(
        info: AudioDeviceInfo,
        adapter: BluetoothAdapter,
    ): Boolean {
        if (!isBluetoothAudioOutputType(info.type)) {
            return false
        }
        // BLEスピーカーは機器種が取れるとは限らないので、型だけで許可する
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            info.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        ) {
            return true
        }
        val device = bluetoothDeviceForOutput(info, adapter) ?: return false
        return isHeadphoneOrSpeakerDevice(device)
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDeviceForOutput(
        info: AudioDeviceInfo,
        adapter: BluetoothAdapter,
    ): BluetoothDevice? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }
        val address = info.address?.trim().orEmpty()
        if (address.isEmpty() || address == "00:00:00:00:00:00") {
            return null
        }
        return runCatching { adapter.getRemoteDevice(address) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun connectedHeadsetOrSpeakerDevices(): List<BluetoothDevice> {
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        return audioBluetoothProfiles().flatMap { profile ->
            runCatching { manager.getConnectedDevices(profile) }.getOrDefault(emptyList())
        }.distinctBy { device ->
            runCatching { device.address }.getOrNull().orEmpty()
        }
    }

    private fun externalMediaPlayingNow(): Boolean {
        val manager = mediaSessionManager ?: return false
        val sessions = runCatching {
            manager.getActiveSessions(listenerComponent)
        }.getOrNull() ?: return false
        return sessions.any { controller ->
            if (controller.packageName == appContext.packageName) {
                return@any false
            }
            isPlayingState(controller.playbackState?.state)
        }
    }

    private companion object {
        const val RefreshIntervalMs = 2_000L

        fun audioBluetoothProfiles(): List<Int> {
            val profiles = mutableListOf(
                BluetoothProfile.A2DP,
                BluetoothProfile.HEADSET,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                profiles += BluetoothProfile.LE_AUDIO
            }
            return profiles
        }

        fun isBluetoothAudioOutputType(type: Int): Boolean {
            return when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                -> true
                else -> {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                }
            }
        }

        /**
         * イヤホン・ヘッドホン・スピーカーのみ。
         * スマートウォッチ（Major.WEARABLE）やカメラ類は除外する。
         */
        @SuppressLint("MissingPermission")
        fun isHeadphoneOrSpeakerDevice(device: BluetoothDevice): Boolean {
            val btClass = runCatching { device.bluetoothClass }.getOrNull() ?: return false
            if (btClass.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE) {
                return false
            }
            if (btClass.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO) {
                return false
            }
            return when (btClass.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE,
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED,
                BluetoothClass.Device.Major.AUDIO_VIDEO,
                -> true
                else -> false
            }
        }

        fun isPlayingState(state: Int?): Boolean {
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
