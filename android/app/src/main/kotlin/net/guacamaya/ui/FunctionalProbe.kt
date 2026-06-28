package net.guacamaya.ui

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlin.math.roundToInt

/** Logcat probe for adb functional tests — no UI. Tag: guacamaya.probe */
@Composable
fun FunctionalProbe(
    headingDeg: Float,
    location: Location?,
    nodes: List<net.guacamaya.mesh.MessageEntity> = emptyList(),
) {
    DisposableEffect(headingDeg, location, nodes) {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                val loc = location
                val nearest = loc?.let { GeoProximity.nearest(it, nodes) }
                if (loc != null) {
                    val acc = if (loc.hasAccuracy()) loc.accuracy else -1f
                    val extra = nearest?.let { t ->
                        " target=${t.nodeId.take(8)} dist_m=${t.distanceMeters.roundToInt()} " +
                            "bearing=${t.bearing.roundToInt()} rel=${
                                if (t.coLocated) 0 else CompassMath.relativeBearing(t.bearing, headingDeg).roundToInt()
                            } co_loc=${t.coLocated}"
                    } ?: ""
                    Log.i(
                        TAG,
                        "heading=${headingDeg.roundToInt()} lat=${loc.latitude} lon=${loc.longitude} " +
                            "acc_m=${acc.roundToInt()} speed=${loc.speed}$extra",
                    )
                } else {
                    Log.i(TAG, "heading=${headingDeg.roundToInt()} lat=null lon=null")
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        handler.post(tick)
        onDispose { handler.removeCallbacks(tick) }
    }
}

private const val TAG = "guacamaya.probe"
private const val INTERVAL_MS = 2_000L
