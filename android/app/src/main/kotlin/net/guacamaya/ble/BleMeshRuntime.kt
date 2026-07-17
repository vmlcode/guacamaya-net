package net.guacamaya.ble

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.guacamaya.BuildConfig
import net.guacamaya.mesh.FloodRouter
import net.guacamaya.mesh.GuacamayaDatabase
import net.guacamaya.mesh.NeighborObservationRecorder
import net.guacamaya.time.MeshClock
import java.util.Base64

/**
 * Shared BLE observer + mesh router. Started from FGS and from MainActivity.onResume
 * so MIUI/API 30 still scans when adb launches the activity briefly in foreground.
 */
object BleMeshRuntime {
    private const val PROBE = "guacamaya.probe"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observer: Observer? = null
    private var router: FloodRouter? = null
    private var controlBroadcaster: ControlBroadcaster? = null
    @Volatile private var radarActive = false
    @Volatile private var controlSink: ControlSink? = null

    /**
     * Plug in the control-frame consumer. [ensureObserving] registers the [FloodRouter]
     * (it implements [ControlSink]); null here would drop control frames on arrival.
     */
    fun setControlSink(sink: ControlSink?) { controlSink = sink }

    fun ensureObserving(ctx: Context): Boolean {
        val app = ctx.applicationContext
        ChatRuntime.initialize(app)
        if (observer == null || router == null) {
            val obs = Observer.create(app) ?: run {
                Log.i(PROBE, "observe fail Observer.create=null")
                return false
            }
            val dao = GuacamayaDatabase.get(app).messageDao()
            val bcast = Broadcaster.create(app)
            // Control relay on its own transport UUID (best-effort; null if the chip
            // cannot advertise — verify + trust state still apply, only relay is lost).
            val ctrlBcast = ControlBroadcaster.create(app)
            val recorder = NeighborObservationRecorder(app, dao, scope)
            val r = FloodRouter(
                dao = dao,
                broadcaster = bcast,
                observationRecorder = recorder,
                scope = scope,
                now = { MeshClock.now(app) },
                // Build-anchored backend pin (never GET /pubkey — see
                // docs/PROTOCOL_CONTROL.md). "" when unset at build →
                // verifyControlFrame treats it as null → only self-cancel (0x03)
                // verifiable. Delivery/backend-resolve require the real pin.
                backendPublicKeyProvider = { BuildConfig.BACKEND_PUBLIC_KEY_HEX },
                controlBroadcaster = ctrlBcast,
            )
            // FloodRouter is the ControlSink: a control PDU routed by UUID from the
            // Observer is enqueued into the same single-consumer coroutine (verify →
            // persistent replay gate → trust state → relay on CONTROL_SERVICE_UUID).
            setControlSink(r)
            controlBroadcaster = ctrlBcast
            obs.setListener { kind, p22, pub32, sig64, ttl, radio ->
                when (kind) {
                    BleConfig.FrameKind.SOS -> r.onFrame(
                        p22,
                        pub32,
                        sig64,
                        ttl,
                        radio.rssi,
                        radio.txPower,
                        radio.primaryPhy,
                        radio.secondaryPhy,
                    )
                    BleConfig.FrameKind.CONTROL -> {
                        // Reassemble the 119 B on-wire blob so the sink can both re-verify
                        // (verifyControlFrame strips the leading TTL) and re-relay it. The
                        // sink is FloodRouter itself (set above); null only if ensure was
                        // never completed → drop, do not relay.
                        val sink = controlSink
                        if (sink == null) {
                            Log.d(PROBE, "control frame dropped (no sink) ttl=$ttl rssi=${radio.rssi}")
                            return@setListener
                        }
                        val onWire = ByteArray(BleConfig.SERVICE_DATA_SIZE)
                        onWire[BleConfig.TTL_OFFSET] = ttl.toByte()
                        System.arraycopy(p22, 0, onWire, BleConfig.PAYLOAD_OFFSET, BleConfig.PUBKEY_OFFSET - BleConfig.PAYLOAD_OFFSET)
                        System.arraycopy(pub32, 0, onWire, BleConfig.PUBKEY_OFFSET, BleConfig.SIG_OFFSET - BleConfig.PUBKEY_OFFSET)
                        System.arraycopy(sig64, 0, onWire, BleConfig.SIG_OFFSET, BleConfig.SERVICE_DATA_SIZE - BleConfig.SIG_OFFSET)
                        sink.onControlFrame(onWire, radio)
                    }
                    BleConfig.FrameKind.CHAT -> Unit // delivered through the raw chat listener
                }
            }
            obs.setChatListener { frame, _ -> ChatRuntime.onFrame(frame) }
            obs.setRadarActive(radarActive)
            observer = obs
            router = r
        }
        val obs = observer ?: return false
        if (obs.isScanning) {
            Log.i(PROBE, "BleMeshRuntime already scanning")
            return true
        }
        obs.start()
        Log.i(PROBE, "BleMeshRuntime scanning=${obs.isScanning} device=${Build.DEVICE}")
        return obs.isScanning
    }

    fun stopObserving() {
        observer?.stop()
        controlBroadcaster?.stop()
        Log.i(PROBE, "BleMeshRuntime stopped")
    }

    /**
     * Feed a backend-minted control frame (delivery 0x01 / backend-resolve 0x02) back into the
     * mesh after a data-mule upload round-trip. [frameB64] is the canonical 118 B upload blob
     * (TTL-stripped) the backend returned in `controlFrames`. We re-prepend the origin hop TTL
     * to rebuild the 119 B on-wire form, then hand it to the router as if the chip had just
     * scanned it — same verify cascade + persistent replay gate + trust-state effect + relay as
     * any other control receipt. RSSI 0 / no txPower / PHY 0 is fine: control frames are not
     * victim-origin RF sources, so they never feed the Sonar observation recorder, and the
     * weak-first relay delay collapses to jitter-only.
     *
     * Best-effort: if the router is null (BLE never came up) the frame is dropped with a log —
     * a later upload re-mints it, and the victim still has the SOS re-flood path.
     */
    fun injectControlFrame(frameB64: String) {
        val router = this.router
        if (router == null) {
            Log.d(PROBE, "control inject dropped (no router)")
            return
        }
        val stripped = try {
            Base64.getDecoder().decode(frameB64)
        } catch (e: IllegalArgumentException) {
            Log.w(PROBE, "control inject dropped (bad base64): ${e.message}")
            return
        }
        // 118 B upload frame → 119 B on-wire (prepend origin hop TTL). Tolerate a full 119 B
        // blob the same way /ingest does by using it as-is.
        val onWire = if (stripped.size == BleConfig.SERVICE_DATA_SIZE) {
            stripped
        } else {
            ByteArray(BleConfig.SERVICE_DATA_SIZE).also { out ->
                out[BleConfig.TTL_OFFSET] = BleConfig.ORIGIN_HOP_TTL.toByte()
                stripped.copyInto(out, BleConfig.PAYLOAD_OFFSET)
            }
        }
        router.onControlFrame(onWire, Observer.RadioMetadata(0, null, 0, 0))
    }

    fun setRadarActive(active: Boolean) {
        radarActive = active
        observer?.setRadarActive(active)
    }

    fun isScanning(): Boolean = observer?.isScanning == true
}

/**
 * Consumes a control frame (delivery / backend-resolve / self-cancel) that the Observer
 * surfaced under [BleConfig.CONTROL_SERVICE_UUID]. [onWire119] is the bare 119 B on-wire
 * blob (leading mutable hop-TTL included); the sink owns the full zero-trust verify +
 * persistent replay gate + trust-state effects. [FloodRouter] implements this.
 *
 * The sink is nullable in [BleMeshRuntime] so the transport compiles standalone; in the
 * live runtime [ensureObserving] sets it to the [FloodRouter], so control PDUs are verified,
 * persisted, and relayed on [BleConfig.CONTROL_SERVICE_UUID].
 */
fun interface ControlSink {
    fun onControlFrame(onWire119: ByteArray, radio: Observer.RadioMetadata)
}
