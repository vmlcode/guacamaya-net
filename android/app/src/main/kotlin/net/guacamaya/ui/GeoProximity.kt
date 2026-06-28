package net.guacamaya.ui

import android.location.Location
import android.os.Build
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * GPS-aware proximity: smooths noisy fixes and snaps to zero when devices are
 * closer than the combined location uncertainty (typical when side-by-side).
 */
object GeoProximity {
    /** Typical phone GPS error when remote accuracy is unknown (m). */
    const val REMOTE_GPS_ACCURACY_M = 12f

    private const val DIST_SMOOTH_ALPHA = 0.18f
    private const val POS_SMOOTH_ALPHA = 0.22f
    private const val RSSI_SMOOTH_ALPHA = 0.25f
    private const val MIN_LOCAL_ACCURACY_M = 4f

    data class Result(
        val nodeId: String,
        val distanceMeters: Float,
        val bearing: Float,
        val rssi: Int,
        val critical: Boolean,
        /** True when GPS noise dominates — distance/bearing unreliable. */
        val coLocated: Boolean,
        val uncertaintyMeters: Float,
        /** Smoothed BLE proximity hint when GPS says «junto». */
        val rssiHint: String? = null,
    )

    private data class NodeFix(
        var lat: Double,
        var lon: Double,
        var rssi: Int,
        var critical: Boolean,
        var updatedAt: Long,
    )

    private val nodeSmooth = LinkedHashMap<String, NodeFix>()
    private val distSmooth = HashMap<String, Float>()

    fun reset() {
        nodeSmooth.clear()
        distSmooth.clear()
    }

    /** Pick nearest node with filtered positions and distance snapping. */
    fun nearest(
        here: Location,
        messages: List<net.guacamaya.mesh.MessageEntity>,
    ): Result? {
        val localAcc = effectiveAccuracy(here)
        val candidates = ArrayList<Result>()

        for (msg in messages) {
            if (msg.latE7 == 0 && msg.lonE7 == 0) continue
            val lat = msg.latE7 / 1e7
            val lon = msg.lonE7 / 1e7
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

            val nodeKey = msg.nodeId.joinToString("") { "%02x".format(it) }
            val smooth = smoothNode(nodeKey, lat, lon, msg.rssi, msg.critical, msg.receivedAt)

            val out = FloatArray(2)
            Location.distanceBetween(here.latitude, here.longitude, smooth.lat, smooth.lon, out)
            val rawDist = out[0]
            val bearing = CompassMath.normalizeDegrees(out[1])

            val uncertainty = localAcc + REMOTE_GPS_ACCURACY_M
            val coLocated = rawDist < uncertainty
            val adjusted = if (coLocated) 0f else max(0f, rawDist - uncertainty * 0.35f)
            val displayDist = smoothDistance(nodeKey, adjusted)

            val candidate = Result(
                nodeId = nodeKey,
                distanceMeters = displayDist,
                bearing = bearing,
                rssi = smooth.rssi,
                critical = smooth.critical,
                coLocated = coLocated,
                uncertaintyMeters = uncertainty,
                rssiHint = if (coLocated) rssiProximityHint(smooth.rssi) else null,
            )
            candidates.add(candidate)
        }

        if (candidates.isEmpty()) return null
        val allCoLocated = candidates.all { it.coLocated }
        return if (allCoLocated) {
            candidates.maxByOrNull { it.rssi }!!
        } else {
            candidates.minByOrNull { it.distanceMeters }!!
        }
    }

    fun formatDistance(result: Result): String {
        val base = formatMeters(result.distanceMeters, result.coLocated)
        val hint = result.rssiHint ?: return base
        return if (result.coLocated) "$base · $hint" else base
    }

    fun rssiProximityHint(smoothedRssi: Int): String = when {
        smoothedRssi >= -55 -> "BLE tocando"
        smoothedRssi >= -65 -> "BLE ~1 m"
        smoothedRssi >= -75 -> "BLE ~3 m"
        else -> "BLE lejos"
    }

    private fun smoothRssi(prev: Int, raw: Int): Int {
        if (prev == 0) return raw
        return (prev + RSSI_SMOOTH_ALPHA * (raw - prev)).roundToInt()
    }

    fun formatMeters(meters: Float, coLocated: Boolean = false): String = when {
        coLocated || meters < 0.5f -> "junto"
        meters < 10f -> {
            val cm = (meters * 100f).roundToInt()
            if (cm < 100) "${cm} cm" else "%.1f m".format(meters)
        }
        meters < 1_000f -> "${meters.roundToInt()} m"
        else -> "%.1f km".format(meters / 1_000f)
    }

    /** Blend successive GPS fixes to reduce 1 m / 3 m jumps on a stationary phone. */
    fun smoothLocation(prev: Location?, next: Location): Location {
        if (prev == null) return next
        val alpha = when {
            !next.hasAccuracy() -> POS_SMOOTH_ALPHA
            next.accuracy > 25f -> 0.10f
            next.accuracy > 10f -> 0.15f
            else -> 0.28f
        }
        val out = Location(next)
        out.latitude = prev.latitude + alpha * (next.latitude - prev.latitude)
        out.longitude = prev.longitude + alpha * (next.longitude - prev.longitude)
        if (next.hasAccuracy() && prev.hasAccuracy()) {
            out.accuracy = prev.accuracy + alpha * (next.accuracy - prev.accuracy)
        }
        return out
    }

    private fun effectiveAccuracy(here: Location): Float {
        val acc = if (here.hasAccuracy()) here.accuracy else REMOTE_GPS_ACCURACY_M
        return max(acc, MIN_LOCAL_ACCURACY_M)
    }

    private fun smoothNode(
        key: String,
        lat: Double,
        lon: Double,
        rssi: Int,
        critical: Boolean,
        at: Long,
    ): NodeFix {
        val prev = nodeSmooth[key]
        if (prev == null) {
            val fix = NodeFix(lat, lon, rssi, critical, at)
            nodeSmooth[key] = fix
            trimNodes()
            return fix
        }
        prev.lat += POS_SMOOTH_ALPHA * (lat - prev.lat)
        prev.lon += POS_SMOOTH_ALPHA * (lon - prev.lon)
        prev.rssi = smoothRssi(prev.rssi, rssi)
        prev.critical = critical
        prev.updatedAt = at
        return prev
    }

    private fun smoothDistance(key: String, raw: Float): Float {
        val prev = distSmooth[key]
        val next = if (prev == null) raw else prev + DIST_SMOOTH_ALPHA * (raw - prev)
        distSmooth[key] = next
        return next
    }

    private fun trimNodes() {
        while (nodeSmooth.size > 256) {
            val oldest = nodeSmooth.entries.minByOrNull { it.value.updatedAt }?.key ?: break
            nodeSmooth.remove(oldest)
            distSmooth.remove(oldest)
        }
    }

    /** Smoothed node offsets for grid map (same filter as radar). */
    fun gridPoints(
        originLat: Double,
        originLon: Double,
        messages: List<net.guacamaya.mesh.MessageEntity>,
    ): List<GridPoint> {
        val seen = LinkedHashSet<String>()
        val points = ArrayList<GridPoint>()
        for (msg in messages) {
            if (msg.latE7 == 0 && msg.lonE7 == 0) continue
            val lat = msg.latE7 / 1e7
            val lon = msg.lonE7 / 1e7
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val key = msg.nodeId.joinToString("") { "%02x".format(it) }
            if (!seen.add(key)) continue
            val smooth = smoothNode(key, lat, lon, msg.rssi, msg.critical, msg.receivedAt)
            val (east, north) = GeoGrid.offsetMeters(originLat, originLon, smooth.lat, smooth.lon)
            points.add(
                GridPoint(
                    id = key.take(8),
                    eastM = east,
                    northM = north,
                    label = msg.sosType.toString(),
                    critical = msg.critical,
                    rssi = smooth.rssi,
                ),
            )
        }
        return points
    }
}

/** Portrait-upright axis remap (phone vertical, not flat on table). */
object CompassAxes {
    fun forDisplayRotation(rotation: Int): Pair<Int, Int> = when (rotation) {
        android.view.Surface.ROTATION_90 -> android.hardware.SensorManager.AXIS_Y to
            android.hardware.SensorManager.AXIS_MINUS_X
        android.view.Surface.ROTATION_180 -> android.hardware.SensorManager.AXIS_MINUS_X to
            android.hardware.SensorManager.AXIS_MINUS_Y
        android.view.Surface.ROTATION_270 -> android.hardware.SensorManager.AXIS_MINUS_Y to
            android.hardware.SensorManager.AXIS_X
        else -> android.hardware.SensorManager.AXIS_X to
            android.hardware.SensorManager.AXIS_MINUS_Y
    }

    /** Some MTK/Xiaomi stacks report azimuth mirrored vs Qualcomm; detect via manufacturer. */
    fun manufacturerCorrectionDeg(): Float = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> 0f // portrait axes fix usually enough; offset stored separately
        else -> 0f
    }
}
