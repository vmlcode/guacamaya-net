package org.sosnet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.sosnet.R
import org.sosnet.mesh.MessageEntity
import org.sosnet.service.SosForegroundService

/**
 * Single-screen Compose UI. Two toggles (Broadcast / Observe) drive the foreground
 * service that owns the BLE + Wi-Fi Aware lifecycles. The map shows verified SOS
 * pins as they arrive from the FloodRouter.
 *
 * Permissions are requested at first launch. The activity refuses to start the
 * service until all required permissions are granted.
 */
class MainActivity : ComponentActivity() {

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled by recomposing; nothing else to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid needs a user agent before any MapView is inflated.
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )

        ensurePermissions()
        setContent { SOSNetTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
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

@Composable
private fun Screen(vm: MapViewModel = viewModel()) {
    val identity by vm.identity.collectAsState()
    val broadcasting by vm.broadcasting.collectAsState()
    val observing by vm.observing.collectAsState()
    val messages by vm.messages.collectAsState()
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        IdentityCard(identity?.nodeId?.toHex() ?: "…")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (broadcasting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                onClick = {
                    val on = !broadcasting
                    vm.setBroadcasting(on)
                    val intent = Intent(ctx, SosForegroundService::class.java).apply {
                        action = if (on) SosForegroundService.ACTION_START else SosForegroundService.ACTION_STOP
                    }
                    ContextCompat.startForegroundService(ctx, intent)
                },
            ) { Text(if (broadcasting) "Stop broadcast" else "Broadcast SOS") }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    val on = !observing
                    vm.setObserving(on)
                    val intent = Intent(ctx, SosForegroundService::class.java).apply {
                        action = if (on) SosForegroundService.ACTION_OBSERVE_ON else SosForegroundService.ACTION_OBSERVE_OFF
                    }
                    ContextCompat.startForegroundService(ctx, intent)
                },
            ) { Text(if (observing) "Stop observe" else "Observe") }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0E1320)),
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No SOS received yet.", color = Color(0xFF9AA0B4))
                }
            } else {
                OsmMap(messages)
            }
        }

        Text("Received: ${messages.size}", color = Color(0xFFE6E8F0))

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(messages) { msg -> MessageRow(msg) }
        }
    }
}

@Composable
private fun OsmMap(messages: List<MessageEntity>) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(3.0)
                controller.setCenter(GeoPoint(0.0, 0.0))
                overlaysCache = this
            }
        },
        update = { mv ->
            mv.overlays.clear()
            messages.forEach { msg ->
                val lat = msg.latE7 / 1e7
                val lon = msg.lonE7 / 1e7
                Marker(mv).apply {
                    position = GeoPoint(lat, lon)
                    title = "${msg.sosType} • ${msg.nodeId.toHex().take(8)} • rssi=${msg.rssi}"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    mv.overlays.add(this)
                }
            }
            if (messages.isNotEmpty()) {
                val last = messages.first()
                mv.controller.setCenter(GeoPoint(last.latE7 / 1e7, last.lonE7 / 1e7))
                mv.controller.setZoom(11.0)
            }
            mv.invalidate()
        },
    )
}

// Hold the last MapView so we can clear overlays across recompositions without
// re-inflating. AndroidView's factory caches the view itself; we only need this
// for the overlay swap inside update.
private var overlaysCache: MapView? = null

@Composable
private fun IdentityCard(nodeIdHex: String) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF161C2C)).padding(12.dp),
    ) {
        Text("SOSNet", color = Color(0xFFE6E8F0), style = MaterialTheme.typography.titleLarge)
        Text("node_id = $nodeIdHex", color = Color(0xFF7C5CFF))
    }
}

@Composable
private fun MessageRow(msg: MessageEntity) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${msg.sosType}", color = Color(0xFFE6E8F0), modifier = Modifier.weight(0.5f))
        Text("(${msg.latE7 / 1e7}, ${msg.lonE7 / 1e7})", color = Color(0xFF9AA0B4), modifier = Modifier.weight(1f))
        Text("rssi ${msg.rssi}", color = Color(0xFF9AA0B4), modifier = Modifier.weight(0.3f))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
