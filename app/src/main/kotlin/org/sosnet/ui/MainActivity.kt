package org.sosnet.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.sosnet.R
import org.sosnet.mesh.MessageEntity
import org.sosnet.permissions.PermissionHelper
import org.sosnet.service.BleServiceState
import org.sosnet.service.SosForegroundService

class MainActivity : ComponentActivity() {

    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* recomposes via ViewModel refresh */ }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* recomposes */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )

        requestMissingPermissions()
        setContent { GuacamayaNetTheme { Surface(Modifier.fillMaxSize()) { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        SosForegroundService.setAppForeground(this, true)
    }

    override fun onPause() {
        SosForegroundService.setAppForeground(this, false)
        super.onPause()
    }

    private fun requestMissingPermissions() {
        val missing = PermissionHelper.missingPermissions(this)
        if (missing.isNotEmpty()) permsLauncher.launch(missing.toTypedArray())
        requestBatteryExemptionIfNeeded()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemptionIfNeeded() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) { }
    }

    fun requestEnableBluetooth() {
        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    fun requestPermissionsAgain() {
        val missing = PermissionHelper.missingPermissions(this)
        if (missing.isNotEmpty()) permsLauncher.launch(missing.toTypedArray())
    }
}

@Composable
private fun Screen(vm: MapViewModel = viewModel()) {
    val ctx = LocalContext.current
    val activity = ctx as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshReadiness()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val identity by vm.identity.collectAsState()
    val serviceState by vm.serviceState.collectAsState()
    val messages by vm.messages.collectAsState()
    val canOperate by vm.canOperate.collectAsState()
    val blockingReason by vm.blockingReason.collectAsState()
    val btOn = vm.isBluetoothEnabled()
    val permsOk = vm.hasAllPermissions()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF161C2C)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Text("🗺") },
                    label = { Text(stringResource(R.string.tab_map)) },
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Text("💬") },
                    label = { Text(stringResource(R.string.tab_messages)) },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IdentityCard(identity?.nodeId?.toHex() ?: "…")
            StatusCard(
                btOn = btOn,
                permsOk = permsOk,
                serviceState = serviceState,
                messageCount = messages.size,
            )

            if (!canOperate) {
                SetupBanner(
                    blockingReason = blockingReason,
                    permsOk = permsOk,
                    btOn = btOn,
                    onGrantPerms = { activity?.requestPermissionsAgain() },
                    onEnableBt = { activity?.requestEnableBluetooth() },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = canOperate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (serviceState.broadcasting) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    ),
                    onClick = {
                        val on = !serviceState.broadcasting
                        val intent = Intent(ctx, SosForegroundService::class.java).apply {
                            action = if (on) SosForegroundService.ACTION_START else SosForegroundService.ACTION_STOP
                        }
                        ContextCompat.startForegroundService(ctx, intent)
                    },
                ) {
                    Text(
                        if (serviceState.broadcasting) stringResource(R.string.sos_stop)
                        else stringResource(R.string.sos_send),
                    )
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f).height(56.dp),
                    enabled = canOperate && (!serviceState.observing || !serviceState.broadcasting),
                    onClick = {
                        val on = !serviceState.observing
                        val intent = Intent(ctx, SosForegroundService::class.java).apply {
                            action = if (on) {
                                SosForegroundService.ACTION_OBSERVE_ON
                            } else {
                                SosForegroundService.ACTION_OBSERVE_OFF
                            }
                        }
                        ContextCompat.startForegroundService(ctx, intent)
                    },
                ) {
                    Text(
                        if (serviceState.observing) stringResource(R.string.observe_stop)
                        else stringResource(R.string.observe_only),
                    )
                }
            }

            if (!vm.isIgnoringBatteryOptimizations()) {
                Text(
                    stringResource(R.string.battery_hint),
                    color = Color(0xFF9AA0B4),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (selectedTab) {
                    0 -> MapTab(messages)
                    1 -> MessagesScreen(messages)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    btOn: Boolean,
    permsOk: Boolean,
    serviceState: BleServiceState.Snapshot,
    messageCount: Int,
) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF161C2C)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.status_card_title), color = Color(0xFFE6E8F0), style = MaterialTheme.typography.titleSmall)
        StatusRow(stringResource(R.string.status_bt), btOn)
        StatusRow(stringResource(R.string.status_perms), permsOk)
        StatusRow(stringResource(R.string.status_observe), serviceState.observing)
        StatusRow(stringResource(R.string.status_broadcast), serviceState.broadcasting)
        Text(
            stringResource(R.string.received_count, messageCount),
            color = Color(0xFF9AA0B4),
            style = MaterialTheme.typography.bodySmall,
        )
        serviceState.lastRssi?.let { rssi ->
            Text(
                stringResource(R.string.status_rssi) + ": " + stringResource(R.string.status_rssi_dbm, rssi),
                color = Color(0xFF9AA0B4),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF9AA0B4), style = MaterialTheme.typography.bodySmall)
        Text(
            if (ok) stringResource(R.string.status_ok) else stringResource(R.string.status_no),
            color = if (ok) Color(0xFF4ADE80) else Color(0xFFF87171),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SetupBanner(
    blockingReason: PermissionHelper.BlockingReason?,
    permsOk: Boolean,
    btOn: Boolean,
    onGrantPerms: () -> Unit,
    onEnableBt: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF2A1F1F)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.setup_required), color = Color(0xFFF87171), style = MaterialTheme.typography.titleSmall)
        val message = when (blockingReason) {
            PermissionHelper.BlockingReason.MissingPermissions -> stringResource(R.string.perm_missing)
            PermissionHelper.BlockingReason.BluetoothOff -> stringResource(R.string.bt_off)
            PermissionHelper.BlockingReason.NoExtendedAdv -> stringResource(R.string.device_no_ble5)
            PermissionHelper.BlockingReason.NoMultipleAdv -> stringResource(R.string.device_no_adv)
            null -> when {
                !permsOk -> stringResource(R.string.perm_missing)
                !btOn -> stringResource(R.string.bt_off)
                else -> ""
            }
        }
        if (message.isNotEmpty()) Text(message, color = Color(0xFFE6E8F0), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!permsOk) {
                OutlinedButton(onClick = onGrantPerms) { Text(stringResource(R.string.grant_permissions)) }
            }
            if (permsOk && !btOn) {
                OutlinedButton(onClick = onEnableBt) { Text(stringResource(R.string.enable_bluetooth)) }
            }
        }
    }
}

@Composable
private fun MapTab(messages: List<MessageEntity>) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0E1320)),
        ) {
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_inbox), color = Color(0xFF9AA0B4))
                }
            } else {
                OsmMap(messages)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(0.4f)) {
            items(messages, key = { it.id }) { msg -> MessageRow(msg) }
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

private var overlaysCache: MapView? = null

@Composable
private fun IdentityCard(nodeIdHex: String) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF161C2C)).padding(12.dp)) {
        Text("Guacamaya-net", color = Color(0xFFE6E8F0), style = MaterialTheme.typography.titleLarge)
        Text("${stringResource(R.string.node_id_label)} = $nodeIdHex", color = Color(0xFF7C5CFF))
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
