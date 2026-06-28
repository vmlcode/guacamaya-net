package net.guacamaya.ui

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import net.guacamaya.mesh.MessageEntity
import kotlin.math.min

private val GridBg = Color(0xFF0E1320)
private val GridLine = Color(0xFF263150)
private val GridLineMajor = Color(0xFF3A4A70)
private val YouDot = Color(0xFF2ED573)
private val NodeSos = Color(0xFFFF3B5C)
private val NodeFind = Color(0xFF35A0FF)
private val NorthNeedle = Color(0xFFE6E8F0)

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

    Canvas(modifier.fillMaxSize()) {
        if (scene.points.isEmpty() && userLocation == null) return@Canvas

        val pad = 24.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val cx = pad + w / 2f
        val cy = pad + h / 2f

        val (maxE, maxN) = GeoGrid.bounds(scene.points)
        val maxExtent = maxOf(maxE, maxN, 2f)
        val scale = min(w, h) / (maxExtent * 2.4f)
        val stepM = GeoGrid.gridStepM(maxExtent)

        drawRect(GridBg)

        // Grid lines (north = up on screen)
        val lines = (maxExtent / stepM).toInt().coerceAtLeast(1) + 1
        for (i in -lines..lines) {
            val offsetPx = i * stepM * scale
            val major = i % 5 == 0
            val color = if (major) GridLineMajor else GridLine
            val stroke = if (major) 1.5f else 1f
            drawLine(color, Offset(cx + offsetPx, pad), Offset(cx + offsetPx, pad + h), strokeWidth = stroke)
            drawLine(color, Offset(pad, cy - offsetPx), Offset(pad + w, cy - offsetPx), strokeWidth = stroke)
        }

        // Crosshair at origin (you)
        drawLine(GridLineMajor, Offset(cx - 12f, cy), Offset(cx + 12f, cy), strokeWidth = 2f)
        drawLine(GridLineMajor, Offset(cx, cy - 12f), Offset(cx, cy + 12f), strokeWidth = 2f)
        drawCircle(YouDot, radius = 8.dp.toPx(), center = Offset(cx, cy))
        drawCircle(Color.White.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = Offset(cx, cy), style = Stroke(2f))

        for (p in scene.points) {
            val px = cx + p.eastM * scale
            val py = cy - p.northM * scale
            val color = if (p.critical) NodeSos else NodeFind
            drawCircle(color, radius = 7.dp.toPx(), center = Offset(px, py))
            drawCircle(color.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = Offset(px, py))
        }

        // North indicator (rotates with compass so it matches radar)
        drawNorthIndicator(cx, pad + 18f, heading)
    }
}

private fun DrawScope.drawNorthIndicator(cx: Float, topY: Float, heading: Float) {
    rotate(-heading, pivot = Offset(cx, topY)) {
        val path = Path().apply {
            moveTo(cx, topY + 14f)
            lineTo(cx - 8f, topY + 28f)
            lineTo(cx + 8f, topY + 28f)
            close()
        }
        drawPath(path, NorthNeedle)
    }
    drawCircle(NorthNeedle.copy(alpha = 0.5f), radius = 3f, center = Offset(cx, topY + 22f))
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
