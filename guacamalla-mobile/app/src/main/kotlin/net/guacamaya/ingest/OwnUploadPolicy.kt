package net.guacamaya.ingest

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides whether THIS device's own SOS frame is worth persisting for upload to
 * the backend (`POST /ingest`). Pure + JVM-testable — no `android.location` deps.
 *
 * Policy (see the data-mule flow in service/GuacamayaForegroundService):
 *   - the FIRST frame of an SOS session always uploads, so a stationary victim
 *     still produces exactly one map pin;
 *   - after that, only when the fix has moved at least [MIN_MOVE_METERS] — tracing
 *     real movement as a trajectory without flooding near-duplicate pins.
 *
 * The mesh broadcast is independent of this: the frame keeps flowing over BLE
 * regardless, and if this phone never gets online another mule relays it.
 */
object OwnUploadPolicy {
    /** Minimum displacement between consecutive uploaded own-frames. */
    const val MIN_MOVE_METERS = 50.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** True if a frame at (latE7, lonE7) should be persisted given the last uploaded one. */
    fun shouldUpload(lastLatE7: Int?, lastLonE7: Int?, latE7: Int, lonE7: Int): Boolean {
        if (lastLatE7 == null || lastLonE7 == null) return true
        return distanceMeters(lastLatE7, lastLonE7, latE7, lonE7) >= MIN_MOVE_METERS
    }

    /** Great-circle distance in metres between two lat/lon points given in 1e7 fixed-point. */
    fun distanceMeters(latE7a: Int, lonE7a: Int, latE7b: Int, lonE7b: Int): Double {
        val lat1 = latE7a / 1e7
        val lon1 = lonE7a / 1e7
        val lat2 = latE7b / 1e7
        val lon2 = lonE7b / 1e7
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }
}
