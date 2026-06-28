package net.guacamaya.ui

import android.location.Location
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/** Logcat probe for adb functional tests — no UI. Tag: guacamaya.probe */
@Composable
fun FunctionalProbe(
    compass: CompassState,
    location: Location?,
    nodes: List<net.guacamaya.mesh.MessageEntity> = emptyList(),
) {
    val snapshot = remember { ProbeSnapshot() }
    snapshot.compass = compass
    snapshot.location = location
    snapshot.nodes = nodes

    DisposableEffect(Unit) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val c = snapshot.compass
                val loc = snapshot.location
                val nearest = loc?.let { GeoProximity.nearest(it, snapshot.nodes) }
                val orient =
                    "pitch=${c.pitchDeg.roundToInt()} roll=${c.rollDeg.roundToInt()} " +
                        "usable=${c.usable} magnet=${magnetLabel(c.magnetAccuracy)}"
                if (loc != null) {
                    val acc = if (loc.hasAccuracy()) loc.accuracy else -1f
                    val extra = nearest?.let { t ->
                        " target=${t.nodeId.take(8)} dist_m=${t.distanceMeters.roundToInt()} " +
                            "bearing=${t.bearing.roundToInt()} rel=${
                                if (t.coLocated) 0 else CompassMath.relativeBearing(t.bearing, c.headingDeg).roundToInt()
                            } co_loc=${t.coLocated}"
                    } ?: ""
                    Log.i(
                        TAG,
                        "heading=${c.headingDeg.roundToInt()} $orient lat=${loc.latitude} lon=${loc.longitude} " +
                            "acc_m=${acc.roundToInt()} speed=${loc.speed}$extra",
                    )
                } else {
                    Log.i(TAG, "heading=${c.headingDeg.roundToInt()} $orient lat=null lon=null")
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        handler.post(tick)
        onDispose { handler.removeCallbacks(tick) }
    }
}

private class ProbeSnapshot {
    var compass: CompassState = CompassState()
    var location: Location? = null
    var nodes: List<net.guacamaya.mesh.MessageEntity> = emptyList()
}

private fun magnetLabel(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "bad"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "med"
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
    else -> "?"
}

private const val TAG = "guacamaya.probe"
private const val INTERVAL_MS = 2_000L
