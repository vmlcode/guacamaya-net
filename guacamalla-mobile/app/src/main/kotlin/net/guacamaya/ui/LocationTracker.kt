package net.guacamaya.ui

import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlin.math.max

/** Fused GPS with outlier rejection — functional layer, no UI. */
object LocationTracker {

    fun smoothFix(prev: Location?, raw: Location): Location {
        if (prev == null) return raw
        val jump = prev.distanceTo(raw)
        val maxJump = max(40f, (prev.accuracy + raw.accuracy) * 2.5f)
        if (jump > maxJump && raw.time - prev.time < 5_000L) {
            return prev
        }
        return GeoProximity.smoothLocation(prev, raw)
    }

    fun buildRequest(highAccuracy: Boolean): LocationRequest =
        LocationRequest.Builder(
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            if (highAccuracy) 500L else 2_000L,
        )
            .setMinUpdateIntervalMillis(if (highAccuracy) 250L else 1_000L)
            .setMaxUpdateDelayMillis(if (highAccuracy) 2_000L else 8_000L)
            .setWaitForAccurateLocation(highAccuracy)
            .build()

    fun listen(
        client: com.google.android.gms.location.FusedLocationProviderClient,
        highAccuracy: Boolean,
        looper: Looper,
        onFix: (Location) -> Unit,
    ): LocationCallback {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val raw = result.lastLocation ?: return
                onFix(raw)
            }
        }
        client.requestLocationUpdates(buildRequest(highAccuracy), callback, looper)
        return callback
    }
}
