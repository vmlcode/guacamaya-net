package net.guacamaya.ui

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import net.guacamaya.mesh.MessageEntity
import kotlin.math.min

// Design tokens from docs/design/DESIGN.md (see GuacamayaPalette / GridMap.kt is Canvas-drawn,
// so it reads raw palette values rather than MaterialTheme roles).
private val GridBg = GuacamayaPalette.Canvas              // near-black canvas
private val GridLine = GuacamayaPalette.Hairline          // minor grid
private val GridLineMajor = GuacamayaPalette.HairlineStrong // major grid
private val YouDot = GuacamayaPalette.Primary             // self = brand yellow
private val NodeSos = GuacamayaPalette.Danger             // critical SOS = danger red
private val NodeFind = GuacamayaPalette.Info              // presence = info blue
private val NorthNeedle = GuacamayaPalette.BodyStrong     // north tick

/**
 * Offline grid map: user at center, nodes in meter offsets. No tile download — low RAM.
 */
@Composable
fun GridMap(
    userLocation: Location?,
    messages: List<MessageEntity>,
    heading: Float,
    modifier: Modifier = Modifier,
) {
    val scene = remember(userLocation, messages) { buildGridScene(userLocation, messages) }
    val accuracyM = userLocation?.takeIf { it.hasAccuracy() }?.accuracy ?: 0f

    Canvas(modifier.fillMaxSize()) {
        if (scene.points.isEmpty() && userLocation == null) return@Canvas

        val pad = 24.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val cx = pad + w / 2f
        val cy = pad + h / 2f

        val (maxE, maxN) = GeoGrid.bounds(scene.points)
        val spanM = CartesianGeo.fitScaleMeters(maxE, maxN, accuracyM)
        val scale = min(w, h) / (spanM * 2.4f)
        val stepM = GeoGrid.gridStepM(spanM)

        drawRect(GridBg)

        // ENU plane rotated so geographic north matches compass (cartesian truth)
        rotate(-heading, pivot = Offset(cx, cy)) {
            val lines = (spanM / stepM).toInt().coerceAtLeast(1) + 1
            for (i in -lines..lines) {
                val offsetPx = i * stepM * scale
                val major = i % 5 == 0
                val color = if (major) GridLineMajor else GridLine
                val stroke = if (major) 1.5f else 1f
                drawLine(color, Offset(cx + offsetPx, cy - h / 2), Offset(cx + offsetPx, cy + h / 2), strokeWidth = stroke)
                drawLine(color, Offset(cx - w / 2, cy - offsetPx), Offset(cx + w / 2, cy - offsetPx), strokeWidth = stroke)
            }

            drawLine(GridLineMajor, Offset(cx - 12f, cy), Offset(cx + 12f, cy), strokeWidth = 2f)
            drawLine(GridLineMajor, Offset(cx, cy - 12f), Offset(cx, cy + 12f), strokeWidth = 2f)

            if (accuracyM > 0f) {
                drawCircle(
                    YouDot.copy(alpha = 0.12f),
                    radius = accuracyM * scale,
                    center = Offset(cx, cy),
                )
            }
            drawCircle(YouDot, radius = 8.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.White.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(2f))

            for (p in scene.points) {
                val px = cx + p.eastM * scale
                val py = cy - p.northM * scale
                val color = if (p.critical) NodeSos else NodeFind
                drawCircle(color, radius = 7.dp.toPx(), center = Offset(px, py))
                drawCircle(color.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = Offset(px, py))
            }
        }

        // Fixed screen-top north label (geo north when phone aligned)
        drawLine(NorthNeedle.copy(alpha = 0.6f), Offset(cx, pad), Offset(cx, pad + 20f), strokeWidth = 2f)
    }
}

private data class GridScene(val points: List<GridPoint>)

private fun buildGridScene(userLocation: Location?, messages: List<MessageEntity>): GridScene {
    val withGps = messages.filter { it.latE7 != 0 || it.lonE7 != 0 }
    if (withGps.isEmpty()) return GridScene(emptyList())

    val originLat: Double
    val originLon: Double
    if (userLocation != null) {
        originLat = userLocation.latitude
        originLon = userLocation.longitude
    } else {
        originLat = withGps.map { it.latE7 / 1e7 }.average()
        originLon = withGps.map { it.lonE7 / 1e7 }.average()
    }

    return GridScene(GeoProximity.gridPoints(originLat, originLon, messages))
}
