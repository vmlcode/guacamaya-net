package net.guacamaya.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.guacamaya.BuildConfig
import net.guacamaya.backend.AlertsRepository
import net.guacamaya.backend.BackendClient
import net.guacamaya.backend.OfficialAlert
import net.guacamaya.backend.ws.LiveSos
import net.guacamaya.backend.ws.LiveSosClient
import net.guacamaya.ble.Broadcaster
import net.guacamaya.crypto.Identity
import net.guacamaya.mesh.MessageDao
import net.guacamaya.mesh.MessageEntity
import net.guacamaya.mesh.GuacamayaDatabase

/**
 * Top-level UI state. Owns:
 *  - the local [Identity] (for the node-id card)
 *  - the message store flow (recent verified SOS)
 *  - broadcast / observe toggles (UI-driven; the actual radio lifecycle is
 *    owned by [GuacamayaForegroundService] once the user starts it)
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val dao: MessageDao = GuacamayaDatabase.get(app).messageDao()

    val messages: StateFlow<List<MessageEntity>> = dao.observeRecent()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** One entry per node_id — latest heartbeat/SOS. */
    val latestNodes: StateFlow<List<MessageEntity>> = dao.observeLatestPerNode()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Total verified frames (all heartbeats). */
    val totalFrames: StateFlow<Int> = dao.observeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Unique devices heard — same as latestNodes.size, from DB. */
    val knownNodes: StateFlow<Int> = dao.observeNodeCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Alias for UI «recibidos» = unique node count. */
    val devicesReceived: StateFlow<Int> = knownNodes

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _broadcasting = MutableStateFlow(false)
    val broadcasting: StateFlow<Boolean> = _broadcasting.asStateFlow()

    private val _observing = MutableStateFlow(false)
    val observing: StateFlow<Boolean> = _observing.asStateFlow()

    /** User-selected operating mode for the big power button. Default: BOTH. */
    private val _mode = MutableStateFlow(MeshMode.BOTH)
    val mode: StateFlow<MeshMode> = _mode.asStateFlow()

    /**
     * Whether this device can transmit SOS over BLE (extended advertising). False only
     * on chips without BLE 5 once Bluetooth is ON — NOT while BT is off (that produced a
     * false "no puede transmitir" warning at launch). Reactive: re-checked whenever the
     * Bluetooth adapter changes state, so enabling BT clears the warning automatically.
     */
    private val _broadcastSupported = MutableStateFlow(Broadcaster.isSupported(app))
    val broadcastSupported: StateFlow<Boolean> = _broadcastSupported.asStateFlow()

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) refreshBroadcastSupport()
        }
    }

    /**
     * Verified official alerts pulled from the optional backend (downlink). Empty
     * when offline or the backend is unreachable — the mesh never depends on it.
     * Only signature-verified records appear here (see [AlertsRepository]).
     */
    private val alertsRepo = AlertsRepository(BackendClient(BuildConfig.BACKEND_BASE_URL))
    private val _alerts = MutableStateFlow<List<OfficialAlert>>(emptyList())
    val alerts: StateFlow<List<OfficialAlert>> = _alerts.asStateFlow()

    /**
     * Live community SOS streamed from the backend over WebSocket (downlink) — reports
     * relayed from other regions/mules, complementing the local BLE mesh. Best-effort:
     * empty when offline; deduped by record id, newest first, capped. See [LiveSosClient].
     */
    private val liveSosClient = LiveSosClient(BuildConfig.BACKEND_BASE_URL)
    private val _liveSos = MutableStateFlow<List<LiveSos>>(emptyList())
    val liveSos: StateFlow<List<LiveSos>> = _liveSos.asStateFlow()

    init {
        viewModelScope.launch {
            _identity.value = Identity.loadOrCreate(app)
        }
        ContextCompat.registerReceiver(
            app,
            btStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshAlerts()
        // Single WS reader thread invokes this callback; the read-modify-write is safe.
        liveSosClient.start(viewModelScope) { sos ->
            val current = _liveSos.value
            if (current.none { it.recordId == sos.recordId }) {
                _liveSos.value = (listOf(sos) + current).take(MAX_LIVE_SOS)
            }
        }
    }

    /** Best-effort pull of verified official alerts. Safe to call on resume / connectivity. */
    fun refreshAlerts() {
        viewModelScope.launch(Dispatchers.IO) {
            alertsRepo.fetchVerifiedAlerts().onSuccess { _alerts.value = it }
        }
    }

    /** Re-evaluate BLE broadcast capability (e.g. after the user turns Bluetooth on). */
    fun refreshBroadcastSupport() {
        _broadcastSupported.value = Broadcaster.isSupported(getApplication())
    }

    override fun onCleared() {
        liveSosClient.stop()
        try { getApplication<Application>().unregisterReceiver(btStateReceiver) } catch (_: Exception) {}
        super.onCleared()
    }

    fun setBroadcasting(on: Boolean) { _broadcasting.value = on }
    fun setObserving(on: Boolean) { _observing.value = on }
    fun setMode(m: MeshMode) { _mode.value = m }

    private companion object {
        const val MAX_LIVE_SOS = 50
    }
}

/** What the big power button does when turned on. */
enum class MeshMode { SOS, FIND, BOTH }
