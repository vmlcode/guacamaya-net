package net.guacamaya.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import net.guacamaya.R
import net.guacamaya.ble.BleMeshRuntime
import net.guacamaya.ble.Broadcaster
import net.guacamaya.crypto.Identity
import net.guacamaya.ingest.IngestUploadWorker
import kotlinx.coroutines.flow.first
import net.guacamaya.mesh.GuacamayaDatabase
import net.guacamaya.proto.Flags
import net.guacamaya.proto.Payload
import net.guacamaya.proto.SosType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps BLE (and, when added, Wi-Fi Aware) alive while
 * the app is backgrounded. Type `connectedDevice` — required for Android 14+
 * to allow the BLE/Wi-Fi state to persist when the screen is off.
 *
 * Action vocabulary:
 *  - ACTION_START         — begin broadcasting a local SOS + observe the mesh
 *  - ACTION_STOP          — stop broadcasting (keep observing)
 *  - ACTION_OBSERVE_ON    — start observer only
 *  - ACTION_OBSERVE_OFF   — stop observer
 */
class GuacamayaForegroundService : Service() {

    private val tag = "guacamaya.service"
    private val probeTag = "guacamaya.probe"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var observeRetryPosted = false
    @Volatile private var wantObserving = false
    private var observeHealthJob: Job? = null

    private var broadcaster: Broadcaster? = null
    private var identity: Identity? = null
    private var heartbeatJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        probeLog("onStartCommand action=${intent?.action} startId=$startId")
        ensureForeground()
        Log.i(tag, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                setWantObserving(true)
                startDistressBroadcast()
            }
            ACTION_STOP -> stopBroadcasting()
            ACTION_OBSERVE_ON -> setWantObserving(true)
            ACTION_OBSERVE_OFF -> setWantObserving(false)
            ACTION_HEARTBEAT_ON -> startPresenceHeartbeat()
            ACTION_HEARTBEAT_OFF -> stopPresenceHeartbeat()
        }
        if (wantObserving) startObserving() else stopObserving()
        // Best-effort data-mule flush: WorkManager waits for connectivity, so this is
        // a no-op until the phone regains Wi-Fi/LTE. Unique work, so it won't pile up.
        IngestUploadWorker.enqueue(this)
        return START_STICKY
    }

    private fun setWantObserving(want: Boolean) {
        wantObserving = want
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WANT_OBSERVE, want).apply()
    }

    private fun restoreWantObserving() {
        if (!wantObserving) {
            wantObserving = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_WANT_OBSERVE, false)
        }
    }

    private fun ensureForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.channel_desc) }
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun ensureObserverAndRouter() {
        // Router lives in BleMeshRuntime; broadcaster may be shared for relay TX.
        if (broadcaster == null) broadcaster = Broadcaster.create(this)
    }

    private fun startObserving() {
        restoreWantObserving()
        if (!wantObserving) {
            probeLog("observe skipped want=false")
            return
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter?.isEnabled != true) {
            Log.w(tag, "observe deferred — Bluetooth off")
            scheduleObserveRetry()
            return
        }
        ensureObserverAndRouter()
        if (!BleMeshRuntime.ensureObserving(this)) scheduleObserveRetry()
        else ensureObserveHealthLoop()
    }

    private fun ensureObserveHealthLoop() {
        if (observeHealthJob?.isActive == true) return
        observeHealthJob = scope.launch {
            val dao = GuacamayaDatabase.get(this@GuacamayaForegroundService).messageDao()
            while (isActive && wantObserving) {
                delay(OBSERVE_HEALTH_MS)
                if (!wantObserving) break
                if (!BleMeshRuntime.isScanning()) {
                    Log.w(tag, "observe health tick — scan inactive, restarting")
                    BleMeshRuntime.ensureObserving(this@GuacamayaForegroundService)
                }
                val nodes = dao.observeNodeCount().first()
                val frames = dao.observeCount().first()
                Log.i(probeTag, "mesh nodes=$nodes frames=$frames scanning=${BleMeshRuntime.isScanning()}")
            }
        }
    }

    private fun startDistressBroadcast() {
        stopPresenceHeartbeat()
        scope.launch {
            val id = identity ?: Identity.loadOrCreate(this@GuacamayaForegroundService).also { identity = it }
            val payload = signedPayload(id, SosType.DISTRESS, critical = true, hopTtl = 15)
            val sig = id.sign(payload)
            val bcast = broadcaster ?: Broadcaster.create(this@GuacamayaForegroundService)?.also { broadcaster = it }
            if (bcast == null) {
                Log.e(tag, "Broadcaster.create failed — extended BLE ADV not supported")
                android.widget.Toast.makeText(
                    this@GuacamayaForegroundService,
                    "BLE broadcast not supported on this device",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } else {
                bcast.start(payload, id.publicKey, sig)
            }
        }
    }

    private fun stopBroadcasting() {
        stopPresenceHeartbeat()
        broadcaster?.stop()
    }

    /**
     * Low-rate signed presence beacon for "Ambos" mode. It lets nearby phones
     * appear on the radar without flooding the mesh as if every node were in SOS.
     */
    private fun startPresenceHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            val id = identity ?: Identity.loadOrCreate(this@GuacamayaForegroundService).also { identity = it }
            val bcast = broadcaster ?: Broadcaster.create(this@GuacamayaForegroundService)?.also { broadcaster = it }
            if (bcast == null) {
                Log.e(tag, "Presence heartbeat unavailable — extended BLE ADV not supported")
                return@launch
            }
            while (isActive) {
                val payload = signedPayload(id, SosType.OTHER, critical = false, hopTtl = PRESENCE_HOP_TTL)
                val sig = id.sign(payload)
                bcast.swap(payload, id.publicKey, sig, PRESENCE_HOP_TTL)
                delay(PRESENCE_INTERVAL_MS + (System.currentTimeMillis() % PRESENCE_JITTER_MS))
            }
        }
    }

    private fun stopPresenceHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun signedPayload(
        id: Identity,
        type: SosType,
        critical: Boolean,
        hopTtl: Int,
    ): ByteArray {
        val (latE7, lonE7) = currentLatLonE7() ?: (0 to 0)
        return Payload(
            latE7 = latE7,
            lonE7 = lonE7,
            tsUnix = System.currentTimeMillis() / 1000,
            nodeId = id.nodeId,
            flags = Flags(hasHeavy = false, critical = critical, batteryBucket = 3, hopTtl = hopTtl),
            sosType = type,
            msgId = (System.currentTimeMillis() and 0xFFFF).toInt(),
        ).encode()
    }

    /**
     * Fetch the device's current position as (latE7, lonE7), or null if location is
     * unavailable (permission denied, services off, or no Google Play Services).
     *
     * Uses FusedLocationProviderClient.lastLocation — a cached one-shot read, which is
     * cheap on battery (no continuous updates) and fine for stamping an SOS. On devices
     * without Google Play Services this resolves to null and the caller falls back.
     */
    @SuppressLint("MissingPermission")
    private suspend fun currentLatLonE7(): Pair<Int, Int>? {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(tag, "location permission not granted; broadcasting without coordinates")
            return null
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        val loc: Location? = suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    Log.w(tag, "lastLocation failed: ${it.message}")
                    cont.resume(null)
                }
        }
        if (loc == null) {
            Log.w(tag, "no last known location available")
            return null
        }
        val latE7 = (loc.latitude * 1e7).toInt().coerceIn(-900_000_000, 900_000_000)
        val lonE7 = (loc.longitude * 1e7).toInt().coerceIn(-1_800_000_000, 1_800_000_000)
        return latE7 to lonE7
    }

    private fun scheduleObserveRetry() {
        if (observeRetryPosted) return
        observeRetryPosted = true
        mainHandler.postDelayed({
            observeRetryPosted = false
            Log.i(tag, "observe retry")
            if (wantObserving) startObserving()
        }, 2_500L)
    }

    private fun stopObserving() {
        observeHealthJob?.cancel()
        observeHealthJob = null
        BleMeshRuntime.stopObserving()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        BleMeshRuntime.stopObserving()
        broadcaster?.stop()
        stopPresenceHeartbeat()
        scope.cancel()
    }

    companion object {
        @Volatile private var instance: GuacamayaForegroundService? = null

        /** Called from MainActivity.onResume while foreground — MIUI needs this for BLE scan. */
        fun kickObserve(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WANT_OBSERVE, true)
                .apply()
            val svc = instance
            if (svc != null) {
                svc.setWantObserving(true)
                svc.startObserving()
            } else {
                Log.i("guacamaya.probe", "kickObserve no instance — start FGS")
                BleMeshRuntime.ensureObserving(context)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, GuacamayaForegroundService::class.java).apply {
                        action = ACTION_OBSERVE_ON
                    },
                )
            }
        }

        const val ACTION_START = "net.guacamaya.action.START"
        const val ACTION_STOP = "net.guacamaya.action.STOP"
        const val ACTION_OBSERVE_ON = "net.guacamaya.action.OBSERVE_ON"
        const val ACTION_OBSERVE_OFF = "net.guacamaya.action.OBSERVE_OFF"
        const val ACTION_HEARTBEAT_ON = "net.guacamaya.action.HEARTBEAT_ON"
        const val ACTION_HEARTBEAT_OFF = "net.guacamaya.action.HEARTBEAT_OFF"

        private const val NOTIF_ID = 0x5050
        private const val CHANNEL_ID = "guacamaya.service"
        private const val PRESENCE_INTERVAL_MS = 60_000L
        private const val PRESENCE_JITTER_MS = 15_000L
        private const val PRESENCE_HOP_TTL = 2
        private const val PREFS = "guacamaya_service"
        private const val KEY_WANT_OBSERVE = "want_observe"
        private const val OBSERVE_HEALTH_MS = 8_000L
    }

    private fun probeLog(msg: String) = Log.i(probeTag, msg)
}
