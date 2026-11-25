package app.smartdisplay.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceCoordinates(
    val latitude: Double,
    val longitude: Double,
)

/**
 * 端末の現在地（最後に取れた座標）。
 * 許可が無い・取れないときは null。日の出・日の入りの計算に使う。
 */
class DeviceLocationHub(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _coordinates = MutableStateFlow(readCached())
    val coordinates: StateFlow<DeviceCoordinates?> = _coordinates.asStateFlow()

    private var listening = false
    private var lastRequestElapsed = 0L
    private val stopTimeout = Runnable { stopUpdates() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            applyLocation(location)
            stopUpdates()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start() {
        refresh(forceUpdate = true)
    }

    fun stop() {
        stopUpdates()
    }

    fun refresh(forceUpdate: Boolean = false) {
        if (!hasPermission()) {
            return
        }
        val latest = bestLastKnown()
        if (latest != null) {
            applyLocation(latest)
        }
        val now = SystemClock.elapsedRealtime()
        val ageNanos = latest?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
        val stale = latest == null || ageNanos == null || ageNanos > MaxAgeMillis * 1_000_000L
        if (!forceUpdate && !stale && now - lastRequestElapsed < MinRequestIntervalMillis) {
            return
        }
        lastRequestElapsed = now
        requestSingleUpdate()
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(): Location? {
        if (locationManager == null || !hasPermission()) {
            return null
        }
        return Providers
            .asSequence()
            .filter { locationManager.isProviderEnabled(it) }
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleUpdate() {
        if (locationManager == null || !hasPermission() || listening) {
            return
        }
        val provider = Providers.firstOrNull { locationManager.isProviderEnabled(it) } ?: return
        listening = true
        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }.onFailure {
            listening = false
            return
        }
        mainHandler.postDelayed(stopTimeout, UpdateTimeoutMillis)
    }

    private fun stopUpdates() {
        mainHandler.removeCallbacks(stopTimeout)
        if (!listening || locationManager == null) {
            listening = false
            return
        }
        listening = false
        runCatching { locationManager.removeUpdates(listener) }
    }

    private fun applyLocation(location: Location) {
        val next = DeviceCoordinates(
            latitude = location.latitude,
            longitude = location.longitude,
        )
        _coordinates.value = next
        prefs.edit()
            .putFloat(KeyLatitude, next.latitude.toFloat())
            .putFloat(KeyLongitude, next.longitude.toFloat())
            .apply()
    }

    private fun readCached(): DeviceCoordinates? {
        if (!prefs.contains(KeyLatitude) || !prefs.contains(KeyLongitude)) {
            return null
        }
        return DeviceCoordinates(
            latitude = prefs.getFloat(KeyLatitude, 0f).toDouble(),
            longitude = prefs.getFloat(KeyLongitude, 0f).toDouble(),
        )
    }

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val PrefsName = "device_location"
        private const val KeyLatitude = "latitude"
        private const val KeyLongitude = "longitude"
        private const val MaxAgeMillis = 6L * 60L * 60L * 1000L
        private const val MinRequestIntervalMillis = 15L * 60L * 1000L
        private const val UpdateTimeoutMillis = 20_000L
        private val Providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}

@Composable
fun rememberDeviceLocationHub(): DeviceLocationHub {
    val context = LocalContext.current
    val hub = remember { DeviceLocationHub(context.applicationContext) }
    DisposableEffect(hub) {
        hub.start()
        onDispose { hub.stop() }
    }
    return hub
}
