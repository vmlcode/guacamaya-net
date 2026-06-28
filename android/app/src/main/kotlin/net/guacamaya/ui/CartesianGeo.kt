package net.guacamaya.ui

import android.location.Location
import kotlin.math.cos
import kotlin.math.sin

/**
 * Local East-North-Up (ENU) plane in meters — uses [Location.distanceBetween]
 * for bearing/distance (more precise than small-angle lat/lon at short range).
 */
object CartesianGeo {

    data class Enu(val eastM: Double, val northM: Double, val distanceM: Float, val bearingDeg: Float)

    fun enuMeters(originLat: Double, originLon: Double, lat: Double, lon: Double): Enu {
        val out = FloatArray(2)
        Location.distanceBetween(originLat, originLon, lat, lon, out)
        val dist = out[0]
        val bearingRad = Math.toRadians(out[1].toDouble())
        val east = dist * sin(bearingRad)
        val north = dist * cos(bearingRad)
        return Enu(east, north, dist, CompassMath.normalizeDegrees(out[1]))
    }

    /** Fast planar fallback when distance already known. */
    fun enuFromBearingDistance(bearingDeg: Float, distanceM: Float): Pair<Float, Float> {
        val rad = Math.toRadians(bearingDeg.toDouble())
        return (distanceM * sin(rad)).toFloat() to (distanceM * cos(rad)).toFloat()
    }

    /** Scale factor: fit all ENU points + margin into view (m per px target). */
    fun fitScaleMeters(maxAbsEast: Float, maxAbsNorth: Float, accuracyM: Float, minSpanM: Float = 4f): Float {
        val span = maxOf(maxAbsEast, maxAbsNorth, accuracyM * 2f, minSpanM)
        return span
    }
}
