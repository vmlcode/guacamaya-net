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
import net.guacamaya.ingest.OwnUploadPolicy
import net.guacamaya.loc.PlatformLocation
import kotlinx.coroutines.flow.first
import net.guacamaya.mesh.GuacamayaDatabase
import net.guacamaya.mesh.MessageDao
import net.guacamaya.mesh.MessageEntity
import net.guacamaya.proto.Flags
import net.guacamaya.proto.Payload
import net.guacamaya.proto.SosType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var broadcastJob: Job? = null

    // Last position whose own-SOS frame we persisted for backend upload, in 1e7
    // fixed-point. Reset per broadcast session so the first frame always uploads.
    private var lastOwnUploadLatE7: Int? = null
    private var lastOwnUploadLonE7: Int? = null

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

    /** SOS mode: this device's own frame is a critical distress request. */
    private fun startDistressBroadcast() =
        startBroadcast(SosType.DISTRESS, ownCritical = true, ownTtl = 15)

    /**
     * "Ambos" mode: own frame is a low-priority presence beacon — but the rotation still
     * runs so this device forwards the SOS frames it is holding. Idempotent.
     */
    private fun startPresenceHeartbeat() {
        if (broadcastJob?.isActive == true) return
        startBroadcast(SosType.OTHER, ownCritical = false, ownTtl = PRESENCE_HOP_TTL)
    }

    /**
     * Store-and-forward broadcast rotation. While active, the single advertising slot
     * alternates between:
     *  - this device's **own** frame (rebuilt each turn for a fresh timestamp/location), and
     *  - the most recent **help-request** frame held for each *other* node (round-robin).
     *
     * This is what lets phone C, arriving after origin A has gone offline, still receive
     * A's signed SOS from a relay B that heard it earlier — A's frame is re-radiated from
     * B's store, not merely echoed once on receipt. Help frames are re-advertised with a
     * small [REBROADCAST_TTL] so each holder spreads them a hop or two; chained holders
     * carry them across the mesh. Receivers accept the aged frames via [net.guacamaya.mesh.AgePolicy].
     */
    private fun startBroadcast(ownType: SosType, ownCritical: Boolean, ownTtl: Int) {
        broadcastJob?.cancel()
        // New session — re-seed so the first own frame is always persisted for upload.
        lastOwnUploadLatE7 = null
        lastOwnUploadLonE7 = null
        broadcastJob = scope.launch {
            val id = identity ?: Identity.loadOrCreate(this@GuacamayaForegroundService).also { identity = it }
            val bcast = broadcaster ?: Broadcaster.create(this@GuacamayaForegroundService)?.also { broadcaster = it }
            if (bcast == null) {
                Log.e(tag, "Broadcaster.create failed — extended BLE ADV not supported")
                // Toast needs a Looper thread; this coroutine runs on Dispatchers.Default.
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        this@GuacamayaForegroundService,
                        "Este equipo no puede transmitir SOS por BLE",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            val dao = GuacamayaDatabase.get(this@GuacamayaForegroundService).messageDao()

            // Bootstrap own ADV via start() so the compat→coded PHY negotiation runs once.
            signedPayload(id, ownType, ownCritical, ownTtl).let { p ->
                val sig = id.sign(p)
                bcast.start(p, id.publicKey, sig)
                persistOwnFrameForUpload(dao, id, p, sig)
            }

            var held: List<MessageEntity> = emptyList()
            var idx = 0
            var ownTurn = false // own frame just went out above
            while (isActive) {
                delay(FORWARD_DWELL_MS)
                if (ownTurn) {
                    val p = signedPayload(id, ownType, ownCritical, ownTtl)
                    val sig = id.sign(p)
                    bcast.swap(p, id.publicKey, sig, ownTtl)
                    persistOwnFrameForUpload(dao, id, p, sig)
                    // Refresh the held set each time we return to our own frame.
                    held = dao.latestHelpFramesPerNode(REBROADCAST_NODES)
                        .filterNot { it.nodeId.contentEquals(id.nodeId) }
                    idx = 0
                } else if (held.isNotEmpty()) {
                    val e = held[idx % held.size]
                    idx++
                    bcast.swap(e.payloadRaw, e.pubkey, e.sig, REBROADCAST_TTL)
                }
                ownTurn = !ownTurn
            }
        }
    }

    /**
     * Persist THIS device's own SOS frame so the data-mule uploader (`POST /ingest`)
     * delivers it to the backend whenever the phone has connectivity — the "I have
     * internet, push my own SOS to the backend so it shows on the map" path. The
     * frame keeps flowing on the BLE mesh regardless; if this phone never gets online,
     * another mule relays and uploads it.
     *
     * Throttled by [OwnUploadPolicy]: the first frame of a session always persists
     * (a stationary victim still gets one pin), then only on real movement — so the
     * uploaded points trace a trajectory instead of flooding duplicates. Rows are
     * flagged `own = true` so they stay out of the radar / "devices heard" counts.
     */
    private suspend fun persistOwnFrameForUpload(
        dao: MessageDao,
        id: Identity,
        payload22: ByteArray,
        sig64: ByteArray,
    ) {
        val p = try { Payload.decode(payload22) } catch (_: Exception) { return }
        if (!p.isHelpRequest) return                  // presence beacons are not SOS
        if (p.latE7 == 0 && p.lonE7 == 0) return       // no GPS fix → nothing to map
        if (!OwnUploadPolicy.shouldUpload(lastOwnUploadLatE7, lastOwnUploadLonE7, p.latE7, p.lonE7)) return

        val entity = MessageEntity(
            nodeId = p.nodeId,
            msgId = p.msgId,
            tsUnix = p.tsUnix,
            latE7 = p.latE7,
            lonE7 = p.lonE7,
            sosType = p.sosType.code,
            critical = p.flags.critical,
            hasHeavy = p.flags.hasHeavy,
            hopTtl = p.flags.hopTtl,
            batteryBucket = p.flags.batteryBucket,
            pubkey = id.publicKey,
            payloadRaw = payload22,
            sig = sig64,
            rssi = 0,
            receivedAt = System.currentTimeMillis(),
            own = true,
        )
        if (dao.insert(entity) != -1L) {
            lastOwnUploadLatE7 = p.latE7
            lastOwnUploadLonE7 = p.lonE7
            IngestUploadWorker.enqueue(this@GuacamayaForegroundService)
        }
    }

    private fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
        broadcaster?.stop()
    }

    private fun stopPresenceHeartbeat() {
        broadcastJob?.cancel()
        broadcastJob = null
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
     * without Google Play Services Fused resolves to null, so we fall back to the platform
     * [PlatformLocation.lastKnown] (AOSP LocationManager) — GuacaMalla must geolocate SOS
     * on de-Googled / low-end phones too. Only when both are empty do we broadcast with no
     * coordinates.
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
        val fused: Location? = suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener {
                    Log.w(tag, "lastLocation failed (no GMS?): ${it.message}")
                    cont.resume(null)
                }
        }
        val loc: Location? = fused ?: PlatformLocation.lastKnown(this)?.also {
            Log.i(tag, "using platform LocationManager fix (Fused unavailable)")
        }
        if (loc == null) {
            Log.w(tag, "no last known location available (Fused + platform)")
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
        private const val PRESENCE_HOP_TTL = 2

        /** Store-and-forward rotation: how many held help-nodes to cycle, and the dwell
         *  per advertised frame. Own frame takes every other slot, so own re-airs every
         *  2×dwell and each held frame every ~2×N×dwell. Hop budget for re-aired frames
         *  is small — chained holders carry SOS across the mesh. Tune for reach vs battery. */
        private const val REBROADCAST_NODES = 20
        private const val FORWARD_DWELL_MS = 2_000L
        private const val REBROADCAST_TTL = 2

        private const val PREFS = "guacamaya_service"
        private const val KEY_WANT_OBSERVE = "want_observe"
        private const val OBSERVE_HEALTH_MS = 8_000L
    }

    private fun probeLog(msg: String) = Log.i(probeTag, msg)
}
