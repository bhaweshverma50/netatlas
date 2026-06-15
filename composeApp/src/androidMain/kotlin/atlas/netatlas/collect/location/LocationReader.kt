package atlas.netatlas.collect.location

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresPermission
import atlas.collect.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Reads a single recent GPS fix using only the framework [LocationManager] (no
 * Google Play Services). On API 30+ it uses `getCurrentLocation` for a fresh,
 * one-shot fix; otherwise it requests a single update and falls back to the last
 * known location. Returns `null` if no provider/permission/fix is available.
 */
class LocationReader(context: Context) {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun current(): LocationFix? {
        val lm = lm ?: return null
        val provider = bestProvider(lm) ?: return null
        return try {
            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getCurrentLocation(lm, provider) ?: lastKnown(lm)
            } else {
                requestSingleUpdate(lm, provider) ?: lastKnown(lm)
            }
            location?.toFix()
        } catch (_: SecurityException) {
            null
        }
    }

    private fun bestProvider(lm: LocationManager): String? = when {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
        lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
        else -> null
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun getCurrentLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val executor = Executors.newSingleThreadExecutor()
            val signal = android.os.CancellationSignal()
            lm.getCurrentLocation(provider, signal, executor) { location ->
                executor.shutdown()
                if (cont.isActive) cont.resume(location)
            }
            cont.invokeOnCancellation {
                signal.cancel()
                executor.shutdown()
            }
        }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestSingleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in API 29 but required by the interface pre-30")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(null)
                }
            }
            lm.requestSingleUpdate(provider, listener, appContext.mainLooper)
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun lastKnown(lm: LocationManager): Location? =
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

    private fun Location.toFix(): LocationFix = LocationFix(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else 0.0,
        speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
    )
}
