package com.parachord.android.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot GPS device-location helper backed by [FusedLocationProviderClient]
 * (sibling to iOS #199's `IosLocationManager`).
 *
 * Concerts only need city-level precision, so we ask for
 * [Priority.PRIORITY_BALANCED_POWER_ACCURACY] and a single fix. [getCurrentLocation]
 * NEVER throws — it returns `null` on any denial / failure / timeout so the caller
 * cleanly falls back to the geoIP path. A ~10s hard timeout prevents it from hanging
 * the detect flow (FusedLocation can silently never call back).
 *
 * COARSE permission only — matches the manifest's `ACCESS_COARSE_LOCATION` and iOS's
 * reduced-accuracy fix.
 */
class DeviceLocationProvider(private val context: Context) {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    /** True when [Manifest.permission.ACCESS_COARSE_LOCATION] has been granted. */
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the current device coordinates as (lat, lon), or `null` on
     * missing-permission / failure / timeout. Never throws.
     */
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        if (!hasLocationPermission()) return null
        return try {
            withTimeoutOrNull(TIMEOUT_MS) {
                requestFreshLocation() ?: lastKnownLocation()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation failed", e)
            null
        }
    }

    @Suppress("MissingPermission") // guarded by hasLocationPermission() above
    private suspend fun requestFreshLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cts.token,
            ).addOnSuccessListener { loc ->
                if (cont.isActive) {
                    cont.resume(loc?.let { it.latitude to it.longitude })
                }
            }.addOnFailureListener { e ->
                Log.w(TAG, "getCurrentLocation request failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    @Suppress("MissingPermission") // guarded by hasLocationPermission() above
    private suspend fun lastKnownLocation(): Pair<Double, Double>? =
        suspendCancellableCoroutine { cont ->
            fusedClient.lastLocation
                .addOnSuccessListener { loc ->
                    if (cont.isActive) {
                        cont.resume(loc?.let { it.latitude to it.longitude })
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

    companion object {
        private const val TAG = "DeviceLocationProvider"
        private const val TIMEOUT_MS = 10_000L
    }
}
