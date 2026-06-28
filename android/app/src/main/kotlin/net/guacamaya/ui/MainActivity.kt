package net.guacamaya.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import net.guacamaya.mesh.MessageEntity
import net.guacamaya.service.GuacamayaForegroundService
import kotlin.math.roundToInt

/**
 * VPN-style single screen. One big power button toggles mesh participation
 * (observe + relay). A secondary red button broadcasts the local SOS. The map of
 * received SOS opens as a full-screen overlay so the home stays uncluttered.
 */
class MainActivity : ComponentActivity() {

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled by recomposing; nothing else to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ensurePermissions()
        setContent { GuacamayaTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
        dispatchServiceAction(intent?.action)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatchServiceAction(intent.action)
    }

    /** Route adb `am start -a …` into the foreground service (shell cannot start FGS on API 30+). */
    private fun dispatchServiceAction(action: String?) {
        if (action.isNullOrBlank() ||
            action == Intent.ACTION_MAIN ||
            !action.startsWith("net.guacamaya.action.")
        ) {
            return
        }
        val intent = Intent(this, GuacamayaForegroundService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun ensurePermissions() {
        val required = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 32) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permsLauncher.launch(missing.toTypedArray())
        requestBatteryExemptionIfNeeded()
    }

    private fun requestBatteryExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // OEM may block; user can allow manually in app settings.
        }
    }
}

// ── Palette ────────────────────────────────────────────────────────────────
private val BgTop = Color(0xFF0B1020)
private val BgBottom = Color(0xFF11182B)
private val Card = Color(0xFF161C2C)
private val CardLine = Color(0xFF263150)
private val TextHi = Color(0xFFE6E8F0)
private val TextLo = Color(0xFF8B93AD)
private val Accent = Color(0xFF7C5CFF)
private val Online = Color(0xFF2ED573)
private val Find = Color(0xFF35A0FF)
private val Sos = Color(0xFFFF3B5C)
private val OffRing = Color(0xFF2A3350)

private const val MAP_RENDER_LIMIT = 300
private const val LIST_RENDER_LIMIT = 500

@Composable
private fun Screen(vm: MapViewModel = viewModel()) {
    val identity by vm.identity.collectAsState()
    val broadcasting by vm.broadcasting.collectAsState()
    val observing by vm.observing.collectAsState()
    val mode by vm.mode.collectAsState()
    val messages by vm.messages.collectAsState()
    val totalReceived by vm.totalReceived.collectAsState()
    val knownNodes by vm.knownNodes.collectAsState()
    val ctx = LocalContext.current

    var showMap by remember { mutableStateOf(false) }
    var showRadar by remember { mutableStateOf(false) }
    val running = broadcasting || observing

    fun send(action: String) {
        val intent = Intent(ctx, GuacamayaForegroundService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun stopAll() {
        vm.setBroadcasting(false)
        vm.setObserving(false)
        send(GuacamayaForegroundService.ACTION_STOP)
        send(GuacamayaForegroundService.ACTION_OBSERVE_OFF)
        send(GuacamayaForegroundService.ACTION_HEARTBEAT_OFF)
    }

    fun applyMode(m: MeshMode) {
        when (m) {
            MeshMode.SOS -> {
                vm.setBroadcasting(true); vm.setObserving(false)
                send(GuacamayaForegroundService.ACTION_OBSERVE_OFF)
                send(GuacamayaForegroundService.ACTION_HEARTBEAT_OFF)
                send(GuacamayaForegroundService.ACTION_START)
            }
            MeshMode.FIND -> {
                vm.setBroadcasting(false); vm.setObserving(true)
                send(GuacamayaForegroundService.ACTION_STOP)
                send(GuacamayaForegroundService.ACTION_HEARTBEAT_OFF)
                send(GuacamayaForegroundService.ACTION_OBSERVE_ON)
            }
            MeshMode.BOTH -> {
                vm.setBroadcasting(true); vm.setObserving(true)
                send(GuacamayaForegroundService.ACTION_STOP)
                send(GuacamayaForegroundService.ACTION_OBSERVE_ON)
                send(GuacamayaForegroundService.ACTION_HEARTBEAT_ON)
            }
        }
    }

    fun onPower() {
        if (running) stopAll() else applyMode(mode)
    }

    fun onSelectMode(m: MeshMode) {
        vm.setMode(m)
        if (running) applyMode(m) // re-apply live
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, BgBottom)))) {
        if (showMap) {
            MapScreen(messages, totalReceived = totalReceived, onBack = { showMap = false })
        } else if (showRadar) {
            RadarScreen(messages = messages, onBack = { showRadar = false })
        } else {
            HomeScreen(
                nodeIdHex = identity?.nodeId?.toHex(),
                running = running,
                mode = mode,
                messages = messages,
                totalReceived = totalReceived,
                knownNodes = knownNodes,
                onPower = { onPower() },
                onSelectMode = { onSelectMode(it) },
                onOpenMap = { showMap = true },
                onOpenRadar = { showRadar = true },
            )
        }
    }
}

private fun MeshMode.color(): Color = when (this) {
    MeshMode.SOS -> Sos
    MeshMode.FIND -> Find
    MeshMode.BOTH -> Online
}

private fun MeshMode.label(): String = when (this) {
    MeshMode.SOS -> "SOS"
    MeshMode.FIND -> "Encontrar"
    MeshMode.BOTH -> "Ambos"
}

private fun statusTitle(running: Boolean, mode: MeshMode): String = when {
    !running -> "Desconectado"
    mode == MeshMode.SOS -> "Emitiendo SOS"
    mode == MeshMode.FIND -> "Buscando SOS"
    else -> "Protegido"
}

private fun statusSubtitle(running: Boolean, mode: MeshMode): String = when {
    !running -> "Elige un modo y enciende"
    mode == MeshMode.SOS -> "Tu señal de auxilio se está transmitiendo"
    mode == MeshMode.FIND -> "Escuchando SOS de otros equipos"
    else -> "Visible para otros y buscando dispositivos cercanos"
}

@Composable
private fun HomeScreen(
    nodeIdHex: String?,
    running: Boolean,
    mode: MeshMode,
    messages: List<MessageEntity>,
    totalReceived: Int,
    knownNodes: Int,
    onPower: () -> Unit,
    onSelectMode: (MeshMode) -> Unit,
    onOpenMap: () -> Unit,
    onOpenRadar: () -> Unit,
) {
    val lastRssi = messages.firstOrNull()?.rssi

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(nodeIdHex)

        Spacer(Modifier.height(20.dp))

        ModeSelector(selected = mode, onSelect = onSelectMode)

        Spacer(Modifier.weight(1f))

        PowerButton(active = running, accent = mode.color(), onClick = onPower)

        Spacer(Modifier.height(24.dp))

        Text(
            statusTitle(running, mode),
            color = if (running) mode.color() else TextHi,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            statusSubtitle(running, mode),
            color = TextLo,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        StatsRow(received = totalReceived, nodes = knownNodes, lastRssi = lastRssi)

        Spacer(Modifier.height(12.dp))

        RadarEntry(messages = messages, onClick = onOpenRadar)

        Spacer(Modifier.height(12.dp))

        MapEntryButton(received = totalReceived, onClick = onOpenMap)
    }
}

@Composable
private fun ModeSelector(selected: MeshMode, onSelect: (MeshMode) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Card)
            .border(1.dp, CardLine, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MeshMode.entries.forEach { m ->
            val active = m == selected
            val accent = m.color()
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (active) accent.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(m) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label(),
                    color = if (active) accent else TextLo,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Header(nodeIdHex: String?) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Guacamaya", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Red SOS sin internet", color = TextLo, fontSize = 12.sp)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Card)
                .border(1.dp, CardLine, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "ID ${nodeIdHex?.take(8) ?: "…"}",
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PowerButton(active: Boolean, accent: Color, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "glow")
    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    val ring by animateColorAsState(if (active) accent else OffRing, tween(400), label = "ring")
    val haloAlpha = if (active) glow else 0f

    Box(contentAlignment = Alignment.Center) {
        // Outer pulsing halo (only when active)
        Box(
            Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = haloAlpha * 0.25f)),
        )
        Box(
            Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        if (active) listOf(accent.copy(alpha = 0.22f), Color(0xFF0E1320))
                        else listOf(Color(0xFF1A2236), Color(0xFF11182B)),
                    )
                )
                .border(3.dp, ring, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            PowerGlyph(color = if (active) accent else TextLo)
        }
    }
}

@Composable
private fun PowerGlyph(color: Color) {
    Canvas(Modifier.size(72.dp)) {
        val sw = 7.dp.toPx()
        val r = size.minDimension / 2 - sw
        val cx = size.width / 2
        val cy = size.height / 2
        drawArc(
            color = color,
            startAngle = 110f,
            sweepAngle = 320f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(cx, cy - r * 1.05f),
            end = Offset(cx, cy - r * 0.15f),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun StatsRow(received: Int, nodes: Int, lastRssi: Int?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatCell("Recibidos", received.toString(), Modifier.weight(1f))
        StatCell("Nodos", nodes.toString(), Modifier.weight(1f))
        StatCell("Señal", lastRssi?.let { "$it" } ?: "—", Modifier.weight(1f), suffix = if (lastRssi != null) "dBm" else null)
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier, suffix: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, CardLine, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (suffix != null) Text(suffix, color = TextLo, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextLo, fontSize = 12.sp)
    }
}

@Composable
private fun RadarEntry(messages: List<MessageEntity>, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val heading = rememberCompassHeading()
    val location = rememberLiveLocation(ctx, highAccuracy = false)
    val target = nearestTarget(location, messages)
    val relative = target?.let { CompassMath.relativeBearing(it.bearing, heading) } ?: 0f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, CardLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RadarCompass(sizeDp = 96, heading = heading, relative = relative, target = target)

        Column(Modifier.weight(1f)) {
            Text("Radar", color = TextHi, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (target == null) {
                Text("Sin objetivo con GPS aún", color = TextLo, fontSize = 13.sp)
                Text("Tocar para abrir radar completo", color = TextLo, fontSize = 11.sp)
            } else {
                Text("Nodo ${target.nodeId.take(8)}", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("${formatDistance(target.distanceMeters)} · abrir radar", color = TextLo, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RadarScreen(messages: List<MessageEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val heading = rememberCompassHeading()
    val location = rememberLiveLocation(ctx, highAccuracy = true)
    val target = nearestTarget(location, messages)
    val relative = target?.let { CompassMath.relativeBearing(it.bearing, heading) } ?: 0f

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Card)
                    .border(1.dp, CardLine, RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("‹ Volver", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            Text("Radar", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.weight(0.7f))

        RadarCompass(sizeDp = 280, heading = heading, relative = relative, target = target)

        Spacer(Modifier.height(28.dp))

        if (target == null) {
            Text("Sin objetivo con GPS", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Enciende Ambos en el otro teléfono y espera el heartbeat.",
                color = TextLo,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                formatDistance(target.distanceMeters),
                color = if (target.critical) Sos else Find,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("hacia nodo ${target.nodeId.take(8)}", color = TextHi, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            Text("${relative.roundToInt()}° relativo · bearing ${target.bearing.roundToInt()}° · brújula ${heading.roundToInt()}°", color = TextLo, fontSize = 12.sp)
            Text("rssi ${target.rssi} dBm · ${if (target.critical) "SOS" else "presencia"}", color = TextLo, fontSize = 12.sp)
        }

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Card)
                .border(1.dp, CardLine, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Text("Precisión", color = TextHi, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("GPS local: ${location?.accuracy?.roundToInt()?.let { "±$it m" } ?: "sin fix"}", color = TextLo, fontSize = 13.sp)
            Text("Objetivos con GPS: ${messages.count { it.latE7 != 0 || it.lonE7 != 0 }}", color = TextLo, fontSize = 13.sp)
            Text("Nota: BLE RSSI no da dirección; la flecha usa GPS + brújula.", color = TextLo, fontSize = 12.sp)
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RadarCompass(sizeDp: Int, heading: Float, relative: Float, target: RadarTarget?) {
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        // Rose rotates so N aligns with true north; arrow shows target relative to phone top.
        Box(Modifier.fillMaxSize().rotate(-heading), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(color = CardLine, radius = r, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = CardLine.copy(alpha = 0.55f), radius = r * 0.66f, style = Stroke(width = 1.dp.toPx()))
                drawCircle(color = CardLine.copy(alpha = 0.35f), radius = r * 0.33f, style = Stroke(width = 1.dp.toPx()))
                drawLine(TextLo.copy(alpha = 0.35f), Offset(r, 0f), Offset(r, size.height), strokeWidth = 1.dp.toPx())
                drawLine(TextLo.copy(alpha = 0.35f), Offset(0f, r), Offset(size.width, r), strokeWidth = 1.dp.toPx())
            }
            Text(
                "N",
                color = TextLo,
                fontSize = (sizeDp / 10).sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
            )
        }
        Text(
            "▲",
            color = target?.let { if (it.critical) Sos else Find } ?: TextLo,
            fontSize = (sizeDp / 3).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.rotate(relative),
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun rememberLiveLocation(ctx: Context, highAccuracy: Boolean): Location? {
    var location by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(ctx, highAccuracy) {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            onDispose { }
        } else {
            val client = LocationServices.getFusedLocationProviderClient(ctx)
            val request = LocationRequest.Builder(
                if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                if (highAccuracy) 1_000L else 5_000L,
            ).setMinUpdateIntervalMillis(if (highAccuracy) 500L else 2_000L).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    location = result.lastLocation ?: location
                }
            }
            client.lastLocation.addOnSuccessListener { if (it != null) location = it }
            client.requestLocationUpdates(request, callback, ctx.mainLooper)
            onDispose { client.removeLocationUpdates(callback) }
        }
    }
    return location
}

private fun nearestTarget(location: Location?, messages: List<MessageEntity>): RadarTarget? {
    val here = location ?: return null
    return messages
        .asSequence()
        .filter { it.latE7 != 0 || it.lonE7 != 0 }
        .mapNotNull { msg ->
            val lat = msg.latE7 / 1e7
            val lon = msg.lonE7 / 1e7
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val out = FloatArray(2)
            Location.distanceBetween(here.latitude, here.longitude, lat, lon, out)
            RadarTarget(
                nodeId = msg.nodeId.toHex(),
                distanceMeters = out[0],
                bearing = CompassMath.normalizeDegrees(out[1]),
                rssi = msg.rssi,
                critical = msg.critical,
            )
        }
        .minByOrNull { it.distanceMeters }
}

private data class RadarTarget(
    val nodeId: String,
    val distanceMeters: Float,
    val bearing: Float,
    val rssi: Int,
    val critical: Boolean,
)

private fun formatDistance(meters: Float): String =
    if (meters < 1_000f) "${meters.roundToInt()} m" else "%.1f km".format(meters / 1_000f)

@Composable
private fun MapEntryButton(received: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, CardLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (received > 0) "Ver mapa de SOS ($received)" else "Ver mapa de SOS",
            color = TextHi,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun MapScreen(messages: List<MessageEntity>, totalReceived: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val userLocation = rememberLiveLocation(ctx, highAccuracy = false)
    val heading = rememberCompassHeading()
    val mapMessages = messages.take(MAP_RENDER_LIMIT)
    val listMessages = messages.take(LIST_RENDER_LIMIT)
    val nodesOnGrid = mapMessages.count { it.latE7 != 0 || it.lonE7 != 0 }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Card)
                    .border(1.dp, CardLine, RoundedCornerShape(12.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("‹ Volver", color = TextHi, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            Column(horizontalAlignment = Alignment.End) {
                Text("Recibidos: $totalReceived", color = TextHi, fontSize = 14.sp)
                Text("Cuadrícula · $nodesOnGrid con GPS", color = TextLo, fontSize = 11.sp)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0E1320))) {
            if (nodesOnGrid == 0 && userLocation == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sin nodos con GPS aún.", color = TextLo)
                }
            } else {
                GridMap(userLocation = userLocation, messages = mapMessages, heading = heading)
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items(listMessages, key = { "${it.nodeId.toHex()}-${it.msgId}" }) { msg -> MessageRow(msg) }
        }
    }
}

@Composable
private fun MessageRow(msg: MessageEntity) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${msg.sosType}", color = TextHi, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.5f))
        Text("(${msg.latE7 / 1e7}, ${msg.lonE7 / 1e7})", color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("rssi ${msg.rssi}", color = TextLo, fontSize = 12.sp, modifier = Modifier.weight(0.4f))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
