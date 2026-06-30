package net.guacamaya.loc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Platform GPS via [LocationManager] — the offline / GMS-less fallback for
 * [com.google.android.gms.location.FusedLocationProviderClient].
 *
 * GuacaMalla targets low-end and de-Googled phones where Google Play Services may be
 * absent or disabled; on those, Fused silently returns null and the device would stamp
 * SOS frames with no coordinates and show an empty radar. This path uses the AOSP
 * [LocationManager] directly (GPS / NETWORK / PASSIVE providers) so own-position works
 * without Google. Same `ACCESS_FINE/COARSE_LOCATION` permissions — no extra manifest
 * entries, no new dependency.
 *
 * Callers should prefer Fused when Play Services is present (better fusion/battery) and
 * fall back here; see the service's frame stamping and the radar's own-position.
 */
object PlatformLocation {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    /** Freshest last-known fix across enabled providers, or null. Cheap one-shot — no updates. */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        var best: Location? = null
        for (p in PROVIDERS) {
            val loc = try {
                if (lm.isProviderEnabled(p)) lm.getLastKnownLocation(p) else null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        return best
    }

    /**
     * Continuous updates on GPS + NETWORK. Returns a stop lambda, or null when no
     * provider could be registered (no permission, services off, no GPS hardware).
     */
    @SuppressLint("MissingPermission")
    fun listen(ctx: Context, looper: Looper, onFix: (Location) -> Unit): (() -> Unit)? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val listener = LocationListener { onFix(it) }
        var registered = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener, looper)
                    registered = true
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        if (!registered) return null
        return { lm.removeUpdates(listener) }
    }

    private const val MIN_INTERVAL_MS = 1_000L
    private const val MIN_DISTANCE_M = 0f
}
