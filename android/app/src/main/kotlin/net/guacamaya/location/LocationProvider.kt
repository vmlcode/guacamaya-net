package net.guacamaya.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort location access for rescue payload stamping and radar.
 *
 * Prefer Google Play Services' fused provider when it exists, but always keep a
 * platform LocationManager path so low-end / de-Googled devices can still stamp
 * SOS frames with their last known coordinates.
 */
object LocationProvider {
    private const val FUSED_TIMEOUT_MS = 1_500L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun lastKnown(context: Context, logTag: String): Location? {
        if (!hasPermission(context)) {
            Log.w(logTag, "location permission not granted; broadcasting without coordinates")
            return null
        }
        fusedLastKnown(context, logTag)?.let { return it }
        return platformLastKnown(context, logTag)
    }

    fun toE7(location: Location): Pair<Int, Int> {
        val latE7 = (location.latitude * 1e7).toInt().coerceIn(-900_000_000, 900_000_000)
        val lonE7 = (location.longitude * 1e7).toInt().coerceIn(-1_800_000_000, 1_800_000_000)
        return latE7 to lonE7
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedLastKnown(context: Context, logTag: String): Location? =
        try {
            withTimeoutOrNull(FUSED_TIMEOUT_MS) {
                val client = LocationServices.getFusedLocationProviderClient(context)
                suspendCancellableCoroutine { cont ->
                    client.lastLocation
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener {
                            Log.w(logTag, "fused lastLocation failed: ${it.message}")
                            cont.resume(null)
                        }
                }
            }
        } catch (t: Throwable) {
            // Includes missing/old Google Play Services on AOSP-style devices.
            Log.w(logTag, "fused location unavailable: ${t.message}")
            null
        }

    @SuppressLint("MissingPermission")
    fun platformLastKnown(context: Context, logTag: String): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = preferredProviders(highAccuracy = true).filter { provider ->
            try {
                manager.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }.ifEmpty {
            try {
                manager.getProviders(true)
            } catch (_: Exception) {
                emptyList()
            }
        }
        val best = providers.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (se: SecurityException) {
                Log.w(logTag, "platform lastKnown denied: ${se.message}")
                null
            } catch (t: Throwable) {
                Log.w(logTag, "platform lastKnown($provider) failed: ${t.message}")
                null
            }
        }.maxWithOrNull(compareBy<Location> { it.time }.thenBy { it.accuracy })
        if (best == null) Log.w(logTag, "no platform last known location available")
        return best
    }

    @SuppressLint("MissingPermission")
    fun listenPlatform(
        context: Context,
        highAccuracy: Boolean,
        looper: Looper,
        logTag: String,
        onFix: (Location) -> Unit,
    ): PlatformSubscription? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android framework")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val providers = preferredProviders(highAccuracy).filter { provider ->
            try {
                manager.isProviderEnabled(provider)
            } catch (_: Exception) {
                false
            }
        }
        if (providers.isEmpty()) {
            Log.w(logTag, "no enabled platform location providers")
            return null
        }
        val minTimeMs = if (highAccuracy) 500L else 2_000L
        val minDistanceM = if (highAccuracy) 0f else 1f
        val registered = providers.mapNotNull { provider ->
            try {
                manager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper)
                provider
            } catch (se: SecurityException) {
                Log.w(logTag, "platform updates denied: ${se.message}")
                null
            } catch (t: Throwable) {
                Log.w(logTag, "platform updates($provider) failed: ${t.message}")
                null
            }
        }
        return if (registered.isEmpty()) {
            null
        } else {
            PlatformSubscription(manager, listener)
        }
    }

    private fun preferredProviders(highAccuracy: Boolean): List<String> =
        if (highAccuracy) {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }

    class PlatformSubscription internal constructor(
        private val manager: LocationManager,
        private val listener: LocationListener,
    ) {
        fun close() {
            manager.removeUpdates(listener)
        }
    }
}
