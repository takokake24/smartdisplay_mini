package app.smartdisplay.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** インターネット到達が確認できないときに通信を打ち切る。 */
class NoInternetException : IOException("インターネットに接続されていません")

/**
 * 端末のデフォルト回線がインターネット到達済みかどうか。
 * [hasInternet] は即時値。画面と読み上げ用の [online] は、切断の瞬間のちらつきを避けるため少し遅らせる。
 */
object InternetConnectivity {
    private const val OfflineConfirmMs = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    @Volatile
    private var hasInternetNow = true

    private var started = false
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var offlineConfirmJob: Job? = null

    fun hasInternet(): Boolean = hasInternetNow

    fun start(context: Context) {
        if (started) {
            return
        }
        started = true
        runCatching { install(context.applicationContext) }
            .onFailure { started = false }
    }

    fun stop() {
        offlineConfirmJob?.cancel()
        offlineConfirmJob = null
        val manager = connectivityManager
        val registered = callback
        if (manager != null && registered != null) {
            runCatching { manager.unregisterNetworkCallback(registered) }
        }
        callback = null
        connectivityManager = null
        started = false
    }

    private fun install(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        connectivityManager = manager
        applySnapshot(confirmOffline = false)
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                applySnapshot(confirmOffline = true)
            }

            override fun onLost(network: Network) {
                applySnapshot(confirmOffline = true)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                applySnapshot(confirmOffline = true)
            }
        }
        callback = registered
        manager.registerDefaultNetworkCallback(registered)
    }

    private fun applySnapshot(confirmOffline: Boolean) {
        val onlineNow = currentNetworkHasInternet()
        hasInternetNow = onlineNow
        if (onlineNow) {
            offlineConfirmJob?.cancel()
            offlineConfirmJob = null
            _online.value = true
            return
        }
        if (!confirmOffline) {
            offlineConfirmJob?.cancel()
            offlineConfirmJob = null
            _online.value = false
            return
        }
        if (!_online.value) {
            return
        }
        if (offlineConfirmJob?.isActive == true) {
            return
        }
        offlineConfirmJob = scope.launch {
            delay(OfflineConfirmMs)
            if (!hasInternetNow) {
                _online.value = false
            }
        }
    }

    private fun currentNetworkHasInternet(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
