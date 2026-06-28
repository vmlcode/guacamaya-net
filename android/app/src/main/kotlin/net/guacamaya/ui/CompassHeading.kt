package net.guacamaya.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * True-north heading in degrees [0, 360) for portrait-upright use.
 * Prefers geomagnetic rotation vector; optional persisted offset corrects OEM mirroring.
 */
object CompassMath {
    private const val SMOOTH_ALPHA = 0.15f
    private const val PREFS = "guacamaya_compass"
    private const val KEY_OFFSET = "heading_offset_deg"

    fun remappedAzimuth(rotationMatrix: FloatArray, displayRotation: Int): Float =
        remappedOrientation(rotationMatrix, displayRotation)[0]

    /** Azimuth°, pitch°, roll° — portrait upright. */
    fun remappedOrientation(rotationMatrix: FloatArray, displayRotation: Int): FloatArray {
        val remapped = FloatArray(9)
        val (axisX, axisY) = CompassAxes.forDisplayRotation(displayRotation)
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)) {
            System.arraycopy(rotationMatrix, 0, remapped, 0, 9)
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)
        return floatArrayOf(
            normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat()),
            Math.toDegrees(orientation[1].toDouble()).toFloat(),
            Math.toDegrees(orientation[2].toDouble()).toFloat(),
        )
    }

    fun smooth(previous: Float, raw: Float, alpha: Float = SMOOTH_ALPHA): Float {
        val delta = shortestDelta(previous, raw)
        return normalizeDegrees(previous + delta * alpha)
    }

    fun isOrientationUsable(pitchDeg: Float, rollDeg: Float): Boolean {
        return pitchDeg in -65f..65f && rollDeg in -75f..75f
    }

    fun normalizeDegrees(v: Float): Float = ((v % 360f) + 360f) % 360f

    fun shortestDelta(from: Float, to: Float): Float {
        var d = to - from
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    fun relativeBearing(bearing: Float, heading: Float): Float = shortestDelta(heading, bearing)

    fun loadOffset(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_OFFSET, CompassAxes.manufacturerCorrectionDeg())

    fun saveOffset(ctx: Context, offsetDeg: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_OFFSET, normalizeDegrees(offsetDeg))
            .apply()
    }

    /** User tapped while phone top points toward magnetic north — zero the display. */
    fun calibrateAsNorth(ctx: Context, currentDisplayedHeading: Float) {
        saveOffset(ctx, loadOffset(ctx) - currentDisplayedHeading)
    }
}

@Composable
fun rememberCompassHeading(reloadKey: Int = 0): Float {
    val ctx = LocalContext.current
    val displayRotation = LocalView.current.display.rotation
    var heading by remember { mutableFloatStateOf(0f) }
    var offset by remember(reloadKey) { mutableFloatStateOf(CompassMath.loadOffset(ctx)) }

    DisposableEffect(ctx, displayRotation, offset) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotation = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val rotMatrix = FloatArray(9)
        val accelValues = FloatArray(3)
        val magnetValues = FloatArray(3)
        var hasAccel = false
        var hasMagnet = false

        var magnetAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

        fun publishFromMatrix() {
            val o = CompassMath.remappedOrientation(rotMatrix, displayRotation)
            if (!CompassMath.isOrientationUsable(o[1], o[2])) return
            val alpha = when (magnetAccuracy) {
                SensorManager.SENSOR_STATUS_UNRELIABLE -> 0.06f
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0.10f
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 0.14f
                else -> 0.20f
            }
            val corrected = CompassMath.normalizeDegrees(o[0] + offset)
            heading = CompassMath.smooth(heading, corrected, alpha)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
                    Sensor.TYPE_ROTATION_VECTOR,
                    Sensor.TYPE_GAME_ROTATION_VECTOR,
                    -> {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                        publishFromMatrix()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, accelValues, 0, 3)
                        hasAccel = true
                        tryMagnetFallback()
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, magnetValues, 0, 3)
                        hasMagnet = true
                        tryMagnetFallback()
                    }
                }
            }

            private fun tryMagnetFallback() {
                if (rotation != null || !hasAccel || !hasMagnet) return
                if (!SensorManager.getRotationMatrix(rotMatrix, null, accelValues, magnetValues)) return
                publishFromMatrix()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magnetAccuracy = accuracy
            }
        }

        val rate = SensorManager.SENSOR_DELAY_GAME
        if (rotation != null) {
            sm.registerListener(listener, rotation, rate)
        } else {
            sm.registerListener(listener, accel, rate)
            sm.registerListener(listener, magnet, rate)
        }

        onDispose { sm.unregisterListener(listener) }
    }

    return heading
}
