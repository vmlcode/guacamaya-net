package net.guacamaya.ui

import android.location.Location
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Local east/north offsets in meters from an origin lat/lon. */
data class GridPoint(
    val id: String,
    val eastM: Float,
    val northM: Float,
    val label: String,
    val critical: Boolean,
    val rssi: Int,
)

object GeoGrid {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun offsetMeters(originLat: Double, originLon: Double, lat: Double, lon: Double): Pair<Float, Float> {
        val dLat = Math.toRadians(lat - originLat)
        val dLon = Math.toRadians(lon - originLon)
        val cosLat = cos(Math.toRadians(originLat))
        val east = (dLon * cosLat * EARTH_RADIUS_M).toFloat()
        val north = (dLat * EARTH_RADIUS_M).toFloat()
        return east to north
    }

    fun bounds(points: List<GridPoint>): Pair<Float, Float> {
        if (points.isEmpty()) return 1f to 1f
        var maxAbsE = 1f
        var maxAbsN = 1f
        for (p in points) {
            maxAbsE = max(maxAbsE, abs(p.eastM))
            maxAbsN = max(maxAbsN, abs(p.northM))
        }
        return maxAbsE to maxAbsN
    }

    /** Pick a round grid step (m) that keeps ~8–12 lines visible. */
    fun gridStepM(maxExtentM: Float): Float {
        if (maxExtentM <= 5f) return 1f
        val raw = max(maxExtentM / 5f, 10f)
        val steps = floatArrayOf(10f, 20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f)
        return steps.firstOrNull { it >= raw } ?: 5_000f
    }

    fun bearingDegrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Float {
        val out = FloatArray(2)
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, out)
        return CompassMath.normalizeDegrees(out[1])
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}
