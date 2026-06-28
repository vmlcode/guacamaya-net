package net.guacamaya.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * True-north heading in degrees [0, 360), remapped for current display rotation.
 * Uses rotation vector with magnetic+accelerometer fallback for older MTK/Xiaomi stacks.
 */
object CompassMath {
    private const val SMOOTH_ALPHA = 0.12f

    fun remappedAzimuth(rotationMatrix: FloatArray, displayRotation: Int): Float {
        val remapped = FloatArray(9)
        val (axisX, axisY) = when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
        }
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)) {
            System.arraycopy(rotationMatrix, 0, remapped, 0, 9)
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)
        return normalizeDegrees(Math.toDegrees(orientation[0].toDouble()).toFloat())
    }

    fun smooth(previous: Float, raw: Float): Float {
        val delta = shortestDelta(previous, raw)
        return normalizeDegrees(previous + delta * SMOOTH_ALPHA)
    }

    fun normalizeDegrees(v: Float): Float = ((v % 360f) + 360f) % 360f

    /** Signed shortest turn from [from] to [to] in degrees. */
    fun shortestDelta(from: Float, to: Float): Float {
        var d = to - from
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /** Bearing from here to target minus device heading → arrow rotation on screen. */
    fun relativeBearing(bearing: Float, heading: Float): Float = shortestDelta(heading, bearing)
}

@Composable
fun rememberCompassHeading(): Float {
    val ctx = LocalContext.current
    val displayRotation = LocalView.current.display.rotation
    var heading by remember { mutableFloatStateOf(0f) }

    DisposableEffect(ctx, displayRotation) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val rotMatrix = FloatArray(9)
        val accelValues = FloatArray(3)
        val magnetValues = FloatArray(3)
        var hasAccel = false
        var hasMagnet = false

        fun publish(raw: Float) {
            heading = CompassMath.smooth(heading, raw)
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                        publish(CompassMath.remappedAzimuth(rotMatrix, displayRotation))
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
                publish(CompassMath.remappedAzimuth(rotMatrix, displayRotation))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
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
