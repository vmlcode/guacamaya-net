package net.guacamaya.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import net.guacamaya.mesh.MessageEntity
import net.guacamaya.mesh.NodeCatalog
import net.guacamaya.backend.OfficialAlert
import net.guacamaya.service.GuacamayaForegroundService
import org.json.JSONObject
import android.util.Log
import net.guacamaya.util.BatteryHelper
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * VPN-style single screen. One big power button toggles mesh participation
 * (observe + relay). The map of received SOS opens as a full-screen overlay so the
 * home stays uncluttered.
 *
 * Styled to the Guacamalla design system (docs/design/DESIGN.md): yellow + black
 * brand voltage, dark-only, emergency-semantic content colors. Active/broadcasting
 * chrome is brand yellow; the red/blue/green semantics carry content meaning
 * (received SOS, presence, verified-official alerts), not the mode chrome.
 */
class MainActivity : ComponentActivity() {

    private val adbHandler = Handler(Looper.getMainLooper())

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            routeAdbIntent(getIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensurePermissions()
        routeAdbIntent(intent)
        setContent {
            GuacamayaTheme {
                Surface(Modifier.fillMaxSize(), color = GuacamayaPalette.Canvas) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        routeAdbIntent(intent)
    }

    private fun routeAdbIntent(source: Intent?) {
        val action = resolveAdbAction(source)
        Log.i("guacamaya.probe", "routeAdbIntent raw=${source?.action} resolved=$action")
        armAdbSession(action)
        dispatchServiceAction(action)
        if (action == GuacamayaForegroundService.ACTION_OBSERVE_ON ||
            action == GuacamayaForegroundService.ACTION_START
        ) {
            adbHandler.postDelayed({ dispatchServiceAction(resolveAdbAction(getIntent())) }, 800L)
            adbHandler.postDelayed({ dispatchServiceAction(resolveAdbAction(getIntent())) }, 3_000L)
        }
    }

    /** MIUI/singleTop often drops intent.action; extra + prefs survive adb redispatch. */
    private fun resolveAdbAction(source: Intent?): String? {
        source?.getStringExtra(EXTRA_ADB_ACTION)?.let { extra ->
            if (extra.startsWith("net.guacamaya.action.")) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ADB_ACTION, extra).apply()
                return extra
            }
        }
        source?.action?.takeIf { it.startsWith("net.guacamaya.action.") }?.let { act ->
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ADB_ACTION, act).apply()
            return act
        }
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ADB_ACTION, null)
    }

    /** Keep screen on for adb functional tests — MIUI stops FGS+BLE if activity hides instantly. */
    private fun armAdbSession(action: String?) {
        if (action.isNullOrBlank() || !action.startsWith("net.guacamaya.action.")) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeAdbIntent(intent)
    }

    /** Route adb `am start -a …` into the foreground service (shell cannot start FGS on API 30+). */
    private fun dispatchServiceAction(action: String?) {
        if (action.isNullOrBlank() ||
            action == Intent.ACTION_MAIN ||
            !action.startsWith("net.guacamaya.action.")
        ) {
            return
        }
        Log.i("guacamaya.probe", "dispatchServiceAction action=$action")
        val intent = Intent(this, GuacamayaForegroundService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
        if (action == GuacamayaForegroundService.ACTION_OBSERVE_ON ||
            action == GuacamayaForegroundService.ACTION_START
        ) {
            GuacamayaForegroundService.kickObserve(this)
        }
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
        // No auto-popup: MIUI abre PowerDetailActivity y rompe adb/UI. Banner in-app opcional.
    }

    companion object {
        const val EXTRA_ADB_ACTION = "guacamaya_adb_action"
        private const val PREFS = "guacamaya_adb"
        private const val KEY_ADB_ACTION = "last_action"
    }
}

// ── Design tokens (aliases over GuacamayaPalette — see ui/Theme.kt & docs/design/DESIGN.md) ──
private val Canvas0 = GuacamayaPalette.Canvas
private val CardBg = GuacamayaPalette.SurfaceCard
private val CardElevated = GuacamayaPalette.SurfaceElevated
private val CardLine = GuacamayaPalette.Hairline
private val TextHi = GuacamayaPalette.Ink
private val TextLo = GuacamayaPalette.Muted
private val Brand = GuacamayaPalette.Primary       // brand voltage = active/broadcasting + chrome
private val OnBrand = GuacamayaPalette.OnPrimary
private val SuccessC = GuacamayaPalette.Success
private val InfoC = GuacamayaPalette.Info           // presence / official-verified
private val DangerC = GuacamayaPalette.Danger       // critical SOS content
private val OffRing = GuacamayaPalette.HairlineStrong

@Composable
private fun Screen(vm: MapViewModel = viewModel()) {
    val identity by vm.identity.collectAsState()
    val broadcasting by vm.broadcasting.collectAsState()
    val observing by vm.observing.collectAsState()
    val mode by vm.mode.collectAsState()
    val messages by vm.messages.collectAsState()
    val latestNodes by vm.latestNodes.collectAsState()
    val devicesReceived by vm.devicesReceived.collectAsState()
    val totalFrames by vm.totalFrames.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val ctx = LocalContext.current
    val probeCompass = rememberCompassState()
    val probeLocation = rememberLiveLocation(ctx, highAccuracy = false)

    var showRadar by remember { mutableStateOf(false) }
    val running = broadcasting || observing
    FunctionalProbe(compass = probeCompass, location = probeLocation, nodes = latestNodes, totalFrames = totalFrames)

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

    Box(
        Modifier
            .fillMaxSize()
            .background(Canvas0)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (showRadar) {
            RadarScreen(latestNodes = latestNodes, onBack = { showRadar = false })
        } else {
            HomeScreen(
                nodeIdHex = identity?.nodeId?.toHex(),
                running = running,
                mode = mode,
                latestNodes = latestNodes,
                devicesReceived = devicesReceived,
                alerts = alerts,
                onPower = { onPower() },
                onSelectMode = { onSelectMode(it) },
                onOpenRadar = { showRadar = true },
            )
        }
    }
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
    else -> "Conectado"
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
    latestNodes: List<MessageEntity>,
    devicesReceived: Int,
    alerts: List<OfficialAlert>,
    onPower: () -> Unit,
    onSelectMode: (MeshMode) -> Unit,
    onOpenRadar: () -> Unit,
) {
    val lastNode = latestNodes.firstOrNull()
    val lastRssi = lastNode?.rssi
    val lastSeen = lastNode?.receivedAt?.let { NodeCatalog.formatLastHeartbeat(it) }
    val ctx = LocalContext.current
    var showBatteryHint by remember { mutableStateOf(BatteryHelper.shouldShowHint(ctx)) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = Space.md, vertical = Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(nodeIdHex)

        if (alerts.isNotEmpty()) {
            Spacer(Modifier.height(Space.sm))
            AlertsBanner(alerts = alerts)
        }

        if (showBatteryHint) {
            Spacer(Modifier.height(Space.sm))
            BatteryHintBanner(
                isXiaomi = BatteryHelper.isXiaomi(),
                onConfigure = { BatteryHelper.openSettings(ctx) },
                onDismiss = {
                    BatteryHelper.dismissHint(ctx)
                    showBatteryHint = false
                },
            )
        }

        Spacer(Modifier.height(Space.lg))

        ModeSelector(selected = mode, onSelect = onSelectMode)

        Spacer(Modifier.weight(1f))

        PowerButton(active = running, onClick = onPower)

        Spacer(Modifier.height(Space.xl))

        Text(
            statusTitle(running, mode),
            color = if (running) Brand else TextHi,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(Space.xxs))
        Text(
            statusSubtitle(running, mode),
            color = TextLo,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        StatsRow(devices = devicesReceived, lastSeen = lastSeen, lastRssi = lastRssi)

        Spacer(Modifier.height(Space.sm))

        RadarEntry(latestNodes = latestNodes, onClick = onOpenRadar)
    }
}

@Composable
private fun BatteryHintBanner(isXiaomi: Boolean, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(CardBg)
            .border(1.dp, CardLine, MaterialTheme.shapes.medium)
            .padding(Space.sm),
    ) {
        Text(
            if (isXiaomi) "MIUI: activa Autostart y sin restricción" else "Sin restricción de batería",
            color = TextHi,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(Space.xxs))
        Text(
            if (isXiaomi) {
                "Para BLE en segundo plano: Autostart ON y batería «Sin restricciones»."
            } else {
                "Permite que Guacamalla siga escuchando SOS con la pantalla apagada."
            },
            color = TextLo,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(Space.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Button(
                onClick = onConfigure,
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
                shape = MaterialTheme.shapes.small,
            ) { Text("Configurar", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextLo),
            ) { Text("Ahora no", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Channel id → human label for official alerts. */
private fun officialChannelLabel(channel: String): String = when (channel) {
    "alertas" -> "Alerta oficial"
    "refugios" -> "Refugio"
    "ayuda-medica" -> "Ayuda médica"
    else -> channel
}

/** Best-effort (title, body) from the verbatim payload JSON; never throws. */
private fun alertDisplay(payloadRaw: String): Pair<String, String?> = try {
    val o = JSONObject(payloadRaw)
    fun first(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o.optString(k).ifBlank { null } }
    val title = first("titulo", "title", "encabezado") ?: "Mensaje oficial"
    val body = first("mensaje", "message", "body", "descripcion", "detalle")
    title to body
} catch (_: Exception) {
    payloadRaw.take(120) to null
}

/**
 * Read-only banner of backend-verified official alerts (downlink). Only records whose
 * Ed25519 signature checked out reach here (see AlertsRepository). Per DESIGN.md, verified
 * official content reads in `info` blue with a ✓ marker — distinct from community mesh SOS.
 */
@Composable
private fun AlertsBanner(alerts: List<OfficialAlert>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(CardBg)
            .border(1.dp, InfoC.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
            .padding(Space.sm),
    ) {
        Text(
            "✓ ${alerts.size} ${if (alerts.size == 1) "alerta oficial verificada" else "alertas oficiales verificadas"}",
            color = InfoC,
            style = MaterialTheme.typography.titleSmall,
        )
        alerts.take(3).forEach { alert ->
            val (title, body) = alertDisplay(alert.payloadRaw)
            Spacer(Modifier.height(Space.xs))
            Text(
                "${officialChannelLabel(alert.channel)} · $title",
                color = TextHi,
                style = MaterialTheme.typography.titleSmall,
            )
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(body, color = TextLo, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (alerts.size > 3) {
            Spacer(Modifier.height(Space.xxs))
            Text("+${alerts.size - 3} más", color = TextLo, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSelector(selected: MeshMode, onSelect: (MeshMode) -> Unit) {
    val modes = MeshMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(48.dp)) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == selected,
                onClick = { onSelect(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Brand,
                    activeContentColor = OnBrand,
                    activeBorderColor = Brand,
                    inactiveContainerColor = CardBg,
                    inactiveContentColor = TextLo,
                    inactiveBorderColor = CardLine,
                ),
                label = { Text(m.label(), style = MaterialTheme.typography.labelLarge) },
            )
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
            Text("Guacamalla", color = TextHi, style = MaterialTheme.typography.titleLarge)
            Text("Red SOS sin internet", color = TextLo, style = MaterialTheme.typography.labelMedium)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(CardBg)
                .border(1.dp, CardLine, RoundedCornerShape(50))
                .padding(horizontal = Space.sm, vertical = 6.dp),
        ) {
            Text(
                "ID ${nodeIdHex?.take(8) ?: "…"}",
                color = Brand,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PowerButton(active: Boolean, onClick: () -> Unit) {
    // Flat hero button (prototype-aligned). Active = solid brand yellow + black SOS asterisk
    // with an expanding beacon pulse; idle = flat dark surface + muted asterisk.
    val fill by animateColorAsState(if (active) Brand else CardElevated, tween(250), label = "fill")
    val glyph = if (active) OnBrand else TextLo

    Box(contentAlignment = Alignment.Center) {
        if (active) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val p by pulse.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
                label = "pulseProgress",
            )
            // Two staggered rings emanating from the button edge — "broadcasting" beacon.
            PulseRing(progress = p)
            PulseRing(progress = (p + 0.5f) % 1f)
        }
        Box(
            Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(fill)
                .then(if (active) Modifier else Modifier.border(2.dp, OffRing, CircleShape))
                .clickable(
                    onClick = onClick,
                    onClickLabel = if (active) "Apagar la malla" else "Encender la malla",
                ),
            contentAlignment = Alignment.Center,
        ) {
            SosAsteriskGlyph(color = glyph)
        }
    }
}

/** Expanding ring that fades as it grows — the connected-state beacon pulse. */
@Composable
private fun PulseRing(progress: Float) {
    Box(
        Modifier
            .size(200.dp)
            .scale(1f + progress * 0.45f)
            .border(3.dp, Brand.copy(alpha = (1f - progress) * 0.5f), CircleShape),
    )
}

/** SOS beacon mark — an 8-spoke asterisk (✳), drawn flat. */
@Composable
private fun SosAsteriskGlyph(color: Color) {
    Canvas(Modifier.size(100.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rOuter = size.minDimension / 2f
        val rInner = rOuter * 0.14f
        val sw = size.minDimension * 0.12f
        val arms = 8
        for (i in 0 until arms) {
            val ang = (PI * 2f / arms * i).toFloat()
            val dx = cos(ang)
            val dy = sin(ang)
            drawLine(
                color = color,
                start = Offset(cx + dx * rInner, cy + dy * rInner),
                end = Offset(cx + dx * rOuter, cy + dy * rOuter),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun StatsRow(devices: Int, lastSeen: String?, lastRssi: Int?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        StatCell("Dispositivos", devices.toString(), Modifier.weight(1f))
        StatCell("Último", lastSeen ?: "—", Modifier.weight(1f))
        StatCell("Señal", lastRssi?.let { "$it" } ?: "—", Modifier.weight(1f), suffix = if (lastRssi != null) "dBm" else null)
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier, suffix: String? = null) {
    // No surface/box — stats sit directly on the canvas.
    Column(
        modifier.padding(vertical = Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = TextHi, style = MaterialTheme.typography.titleLarge)
        if (suffix != null) Text(suffix, color = TextLo, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextLo, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RadarEntry(latestNodes: List<MessageEntity>, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val heading = rememberCompassHeading()
    val location = rememberLiveLocation(ctx, highAccuracy = false)
    val target = location?.let { GeoProximity.nearest(it, latestNodes) }
    val relative = target?.let {
        if (it.coLocated) 0f else CompassMath.relativeBearing(it.bearing, heading)
    } ?: 0f

    // Compact entry — small compass + one status line + chevron.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(CardBg)
            .border(1.dp, CardLine, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick, onClickLabel = "Abrir radar completo")
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        RadarCompass(sizeDp = 40, heading = heading, relative = relative, target = target)

        Column(Modifier.weight(1f)) {
            Text("Radar", color = TextHi, style = MaterialTheme.typography.titleSmall)
            Text(
                if (target == null) "Sin objetivo con GPS aún"
                else "Nodo ${target.nodeId.take(8)} · ${GeoProximity.formatDistance(target)}",
                color = TextLo,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text("›", color = TextLo, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RadarScreen(latestNodes: List<MessageEntity>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var compassKey by remember { mutableIntStateOf(0) }
    val heading = rememberCompassHeading(reloadKey = compassKey)
    val location = rememberLiveLocation(ctx, highAccuracy = true)
    val target = location?.let { GeoProximity.nearest(it, latestNodes) }
    val targetNode = target?.let { t -> latestNodes.firstOrNull { it.nodeId.toHex() == t.nodeId } }
    val relative = target?.let {
        if (it.coLocated) 0f else CompassMath.relativeBearing(it.bearing, heading)
    } ?: 0f

    Column(
        Modifier.fillMaxSize().padding(Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BackButton(onBack)
            Text("Radar", color = TextHi, style = MaterialTheme.typography.titleLarge)
        }

        Spacer(Modifier.weight(0.7f))

        RadarCompass(sizeDp = 280, heading = heading, relative = relative, target = target)

        Spacer(Modifier.height(Space.xl))

        if (target == null) {
            Text("Sin objetivo con GPS", color = TextHi, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Space.xxs))
            Text(
                "Enciende Ambos en el otro teléfono y espera el heartbeat.",
                color = TextLo,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                GeoProximity.formatDistance(target),
                color = if (target.critical) DangerC else InfoC,
                style = MaterialTheme.typography.displaySmall,
            )
            Text("hacia nodo ${target.nodeId.take(8)}", color = TextHi, style = MaterialTheme.typography.titleMedium)
            targetNode?.let { node ->
                Spacer(Modifier.height(Space.xxs))
                Text(
                    "Último ${NodeCatalog.signalKind(node)}: ${NodeCatalog.formatLastHeartbeat(node.receivedAt)}",
                    color = InfoC,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(Space.xs))
            if (target.coLocated) {
                Text("Dispositivos juntos — GPS no distingue cm", color = TextLo, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Text("Usa la brújula; calibra apuntando al norte", color = TextLo, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            } else {
                Text("${relative.roundToInt()}° relativo · bearing ${target.bearing.roundToInt()}° · brújula ${heading.roundToInt()}°", color = TextLo, style = MaterialTheme.typography.labelMedium)
            }
            Text("rssi ${target.rssi} dBm · ±${target.uncertaintyMeters.roundToInt()} m GPS · ${if (target.critical) "SOS" else "presencia"}", color = TextLo, style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(Space.md))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(CardBg)
                .border(1.dp, CardLine, MaterialTheme.shapes.large)
                .padding(Space.sm),
        ) {
            Text("Precisión", color = TextHi, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Space.xxs))
            Text("GPS local: ${location?.accuracy?.roundToInt()?.let { "±$it m" } ?: "sin fix"}", color = TextLo, style = MaterialTheme.typography.bodySmall)
            Text("Objetivos con GPS: ${latestNodes.count { it.latE7 != 0 || it.lonE7 != 0 }}", color = TextLo, style = MaterialTheme.typography.bodySmall)
            Text("Nota: a <15 m la distancia GPS se muestra como «junto».", color = TextLo, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(Space.xs))
            FilledTonalButton(
                onClick = {
                    CompassMath.calibrateAsNorth(ctx, heading)
                    compassKey++
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CardElevated,
                    contentColor = TextHi,
                ),
                shape = MaterialTheme.shapes.medium,
            ) { Text("Calibrar norte (top del teléfono → N)", style = MaterialTheme.typography.labelLarge) }
        }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RadarCompass(sizeDp: Int, heading: Float, relative: Float, target: GeoProximity.Result?) {
    val arrowAlpha = if (target?.coLocated == true) 0.35f else 1f
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
            color = target?.let { if (it.critical) DangerC else InfoC }?.copy(alpha = arrowAlpha) ?: TextLo.copy(alpha = arrowAlpha),
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
            var current = location
            val callback = LocationTracker.listen(
                client,
                highAccuracy,
                ctx.mainLooper,
            ) { raw ->
                current = LocationTracker.smoothFix(current, raw)
                location = current
            }
            client.lastLocation.addOnSuccessListener { fix ->
                if (fix != null) {
                    current = LocationTracker.smoothFix(current, fix)
                    location = current
                }
            }
            onDispose { client.removeLocationUpdates(callback) }
        }
    }
    return location
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    FilledTonalButton(
        onClick = onBack,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CardBg, contentColor = TextHi),
        shape = MaterialTheme.shapes.medium,
    ) { Text("‹ Volver", style = MaterialTheme.typography.labelLarge) }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
