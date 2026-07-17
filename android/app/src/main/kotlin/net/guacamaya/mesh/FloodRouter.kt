package net.guacamaya.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import net.guacamaya.ble.BleConfig
import net.guacamaya.ble.Broadcaster
import net.guacamaya.ble.ControlRelay
import net.guacamaya.ble.ControlSink
import net.guacamaya.ble.Observer
import net.guacamaya.crypto.Signer
import net.guacamaya.proto.ControlFrameResult
import net.guacamaya.proto.ControlType
import net.guacamaya.proto.Flags
import net.guacamaya.proto.Payload
import net.guacamaya.proto.VerifiedControlFrame
import net.guacamaya.proto.verifyControlFrame
import java.security.MessageDigest

/**
 * Glues the BLE Observer to the rest of the mesh. Implements the reject cascade
 * from docs/crypto.md, cheapest first:
 *
 *  1. SHA-256(pubkey)[0..4] == payload.node_id (else: pubkey swap attack)
 *  2. Payload.decode (CRC check)
 *  3. ts_unix skew ≤ 300 s (replay defense)
 *  4. Ed25519 verify
 *
 * On pass: dedupe → persist → relay. The relay decrements the unsigned hop TTL
 * (carried alongside the signed payload, see ble/BleConfig) and stops once it
 * reaches 0 — this bounds spatial propagation and protects battery. A frame whose
 * TTL is already exhausted is still persisted (so the local user sees it) but not
 * rebroadcast. Loop-back within the TTL window is suppressed by the dedupe cache.
 *
 * The router is single-threaded on a dedicated dispatcher; BLE callbacks arrive
 * on a binder thread, so [onFrame] just enqueues a coroutine.
 */
class FloodRouter(
    private val dao: MessageDao,
    private val dedupe: DedupeCache = DedupeCache(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val broadcaster: Broadcaster? = null,
    private val observationRecorder: NeighborObservationSink? = null,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Pinned backend Ed25519 public key (lowercase hex), or null when none is pinned.
     * Required to accept a delivery (0x01) or backend-resolve (0x02) control frame;
     * null leaves only self-cancel (0x03) verifiable. TOFU-anchored at build time, never
     * from `GET /pubkey` (see docs/PROTOCOL_CONTROL.md). Lazy so the pin can arrive late
     * (e.g. fetched on first ingest) without rebuilding the router.
     */
    private val backendPublicKeyProvider: () -> String? = { null },
    /**
     * Relay path for control frames, advertised under [BleConfig.CONTROL_SERVICE_UUID] —
     * NOT the SOS [Broadcaster]. A control frame relayed on the SOS UUID would be
     * classified as SOS by the Observer and dropped on signature; control must leave on
     * its own transport UUID. Nullable so the router degrades to verify-only (persist +
     * trust state still apply) when the chip cannot advertise.
     */
    private val controlBroadcaster: ControlRelay? = null,
) : ControlSink {
    private val tag = "guacamaya.mesh.FloodRouter"
    private val nodeIdDigest = MessageDigest.getInstance("SHA-256")
    private val frames = Channel<Inbox>(
        capacity = FRAME_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var insertsSincePrune = 0
    private val copiesByMessage = mutableMapOf<String, Int>()
    private val relayScheduled = mutableSetOf<String>()
    private val originBuckets = mutableMapOf<String, TokenBucket>()

    init {
        scope.launch {
            for (item in frames) {
                when (item) {
                    is Inbox.Sos -> handle(
                        item.payload22,
                        item.pub32,
                        item.sig64,
                        item.ttl,
                        item.rssi,
                        item.txPower,
                        item.primaryPhy,
                        item.secondaryPhy,
                    )
                    is Inbox.Control -> handleControlEnvelope(item.onWire119, item.rssi)
                }
            }
        }
    }

    /** Callback for the BLE Observer. Cheap and bounded; drops oldest under storms. */
    fun onFrame(
        payload22: ByteArray,
        pub32: ByteArray,
        sig64: ByteArray,
        ttl: Int,
        rssi: Int,
        txPower: Int? = null,
        primaryPhy: Int = 0,
        secondaryPhy: Int = 0,
    ) {
        val accepted = frames.trySend(
            Inbox.Sos(payload22, pub32, sig64, ttl, rssi, txPower, primaryPhy, secondaryPhy),
        ).isSuccess
        if (!accepted) Log.w(tag, "DROP: frame queue saturated")
    }

    /**
     * [ControlSink] — entry for a control PDU the Observer surfaced under
     * [BleConfig.CONTROL_SERVICE_UUID]. Cheap and bounded; the full verify cascade +
     * persistent replay gate run on the single-consumer coroutine (same thread as SOS,
     * so the mutable relay maps and dao stay race-free). Null sink ⇒ dropped upstream.
     */
    override fun onControlFrame(onWire119: ByteArray, radio: Observer.RadioMetadata) {
        val accepted = frames.trySend(Inbox.Control(onWire119, radio.rssi)).isSuccess
        if (!accepted) Log.w(tag, "DROP: control frame queue saturated")
    }

    /**
     * Verify a control envelope on the consumer thread. The on-wire 119 B blob is fed
     * straight to [verifyControlFrame] (it strips the leading mutable hop TTL itself).
     * Only a fully verified control frame is acted on; any rejection is dropped — there
     * is NO SOS fall-through, because UUID routing already classified this PDU as control
     * (a control UUID never carries an SOS payload).
     */
    private suspend fun handleControlEnvelope(onWire119: ByteArray, rssi: Int) {
        val ttl = onWire119[BleConfig.TTL_OFFSET].toInt() and 0xFF
        when (val result = verifyControlFrame(onWire119, now(), backendPublicKeyProvider())) {
            is ControlFrameResult.Ok -> handleControl(result.control, ttl, rssi)
            is ControlFrameResult.Err -> Log.w(tag, "DROP: control reject (${result.reason})")
        }
    }

    private suspend fun handle(
        payload22: ByteArray,
        pub32: ByteArray,
        sig64: ByteArray,
        ttl: Int,
        rssi: Int,
        txPower: Int?,
        primaryPhy: Int,
        secondaryPhy: Int,
    ) {
        // 1. Pubkey binding (reuse digest — single consumer thread).
        val digest = nodeIdDigest.digest(pub32)
        val expectedNodeId = digest.copyOfRange(0, 4)

        // 2. CRC + structural decode.
        val payload = try {
            Payload.decode(payload22)
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "DROP: decode/CRC failure (${e.message})")
            return
        }

        if (!payload.nodeId.contentEquals(expectedNodeId)) {
            Log.w(tag, "DROP: pubkey/node_id mismatch")
            return
        }

        // 3. Age window — help requests get a long store-and-forward window so a
        //    victim's last-known SOS keeps propagating; presence stays fresh-only.
        val nowSec = now() / 1000
        if (!AgePolicy.accept(payload.tsUnix, nowSec, payload.isHelpRequest)) {
            Log.w(tag, "DROP: age out of window (ts=${payload.tsUnix} now=$nowSec help=${payload.isHelpRequest})")
            return
        }

        // 4. Signature (the expensive gate — last).
        if (!Signer.verify(pub32, payload22, sig64)) {
            Log.w(tag, "DROP: signature invalid")
            return
        }

        // An unchanged TTL is only an untrusted proximity hint: the mutable byte
        // can be copied by a relay/wormhole and never proves physical origin.
        if (ttl == payload.flags.hopTtl) {
            observationRecorder?.record(
                DirectRadioObservation(
                    nodeId = payload.nodeId,
                    rssi = rssi,
                    source = ObservationSource.BLE_RSSI,
                    txPower = txPower,
                    primaryPhy = primaryPhy,
                    secondaryPhy = secondaryPhy,
                    proximityTrusted = false,
                ),
            )
        }

        // Count every valid copy before dedupe. A relay is suppressed when enough
        // neighbours already cover it; far/weak receptions get the shortest delay.
        val messageKey = "${payload.nodeId.toHex()}:${payload.msgId}:${payload.tsUnix}"
        copiesByMessage[messageKey] = (copiesByMessage[messageKey] ?: 0) + 1

        // 6. Dedupe + persist.
        val fresh = dedupe.admit(payload.nodeId, payload.msgId)
        val entity = MessageEntity(
            nodeId = payload.nodeId,
            msgId = payload.msgId,
            tsUnix = payload.tsUnix,
            latE7 = payload.latE7,
            lonE7 = payload.lonE7,
            sosType = payload.sosType.code,
            critical = payload.flags.critical,
            hasHeavy = payload.flags.hasHeavy,
            hopTtl = payload.flags.hopTtl,
            batteryBucket = payload.flags.batteryBucket,
            pubkey = pub32,
            payloadRaw = payload22,
            sig = sig64,
            rssi = rssi,
            receivedAt = now(),
        )
        val inserted = dao.insert(entity)
        if (inserted != -1L) {
            insertsSincePrune++
            if (insertsSincePrune >= PRUNE_EVERY_INSERTS) {
                dao.pruneOldKeeping(MAX_STORED_MESSAGES)
                insertsSincePrune = 0
            }
        }
        Log.i(tag, "OK  node=${payload.nodeId.toHex()} msg=${payload.msgId} type=${payload.sosType} rssi=$rssi fresh=$fresh inserted=${inserted != -1L} ttl_in=$ttl")

        if (!fresh) return  // already flooded within the dedupe window

        // 7. Relay. The signed 22 B payload (+ pubkey + sig) is forwarded UNCHANGED
        //    so the origin's signature still verifies at the next hop — only the
        //    unsigned hop TTL shrinks. Stop once it would reach 0: that bounds how
        //    far the frame travels and keeps idle radios off the air.
        val nextTtl = ttl - 1
        if (nextTtl <= 0) {
            Log.d(tag, "no relay: hop TTL exhausted (ttl_in=$ttl)")
            return
        }
        val bucket = originBuckets.getOrPut(pub32.toHex()) { TokenBucket(now()) }
        if (!bucket.tryTake(now())) {
            Log.w(tag, "no relay: origin rate limit")
            return
        }
        if (!relayScheduled.add(messageKey)) return
        val weakFirstDelay = ((rssi.coerceIn(-100, -35) + 35) * RSSI_DELAY_SPAN_MS / 65).toLong()
        scope.launch {
            delay(weakFirstDelay + (0..RELAY_JITTER_MS).random())
            val copies = copiesByMessage[messageKey] ?: 0
            relayScheduled.remove(messageKey)
            copiesByMessage.remove(messageKey)
            if (copies >= COUNTER_SUPPRESSION_K) {
                Log.d(tag, "no relay: counter suppression copies=$copies")
            } else {
                broadcaster?.swap(payload22, pub32, sig64, nextTtl)
            }
        }
    }

    /**
     * A verified control receipt (delivery 0x01 / backend-resolve 0x02 / self-cancel
     * 0x03). Act on it against this device's own SOS state, then relay — never persist
     * it as an SOS frame. Replay is gated by the persistent [MessageDao.insertControlReplay]
     * (survives a reboot, unlike [DedupeCache]); trust state is pinned per own SOS in
     * [MessageDao.upsertControlTrust]. Control frames are not victim-origin RF sources,
     * so they are excluded from the Sonar observation recorder. Relay fairness reuses the
     * same token-bucket / counter-suppression / weak-first delay as SOS, keyed by issuer.
     */
    private suspend fun handleControl(
        control: VerifiedControlFrame,
        ttl: Int,
        rssi: Int,
    ) {
        val nowMs = now()

        // Persistent replay guard — the cross-reboot gate. -1 (IGNORE) ⇒ a signature we
        // have already acted on: do not re-act, do not re-flood.
        val admitted = dao.insertControlReplay(
            ControlReplayEntity(
                signatureHex = control.signatureHex,
                frameType = control.type.code,
                issuerNodeId = control.issuerNodeIdHex,
                ctrlMsgId = control.ctrlMsgId,
                expiresAt = control.expiresAt * 1_000L,
                receivedAt = nowMs,
            ),
        )
        if (admitted == -1L) {
            Log.d(tag, "control replay sig=${control.signatureHex.take(16)}")
            return
        }
        dao.pruneControlReplay(nowMs) // opportunistic: bounded table, expired rows first

        // Queue verified self-cancels (0x03) for data-mule upload: self-cancel is the
        // only mesh-originated control the backend needs (processControlFrames →
        // SOS_CANCELLED ledger event). Delivery (0x01) / backend-resolve (0x02) are
        // minted server-side, so they are not re-uploaded. insertControlReplay above
        // already rejected a replay, so a given self-cancel is queued at most once;
        // IGNORE on the outbound PK is the belt-and-braces.
        if (control.type == ControlType.SELF_CANCEL) {
            dao.insertOutboundControl(
                OutboundControlEntity(
                    signatureHex = control.signatureHex,
                    frameType = control.type.code,
                    frame = control.frame,
                    queuedAt = nowMs,
                ),
            )
        }

        applyControlTrust(control, nowMs)

        Log.i(
            tag,
            "OK  control type=${control.type} target=${control.targetNodeIdHex} msg=${control.targetMsgId} " +
                "issuer=${control.issuerNodeIdHex} ctrl=${control.ctrlMsgId} ttl_in=$ttl",
        )

        // Relay. The signed 22 B payload (+ issuer pubkey + sig) is forwarded unchanged,
        // only the unsigned hop TTL shrinks — same invariant as SOS relay.
        val nextTtl = ttl - 1
        if (nextTtl <= 0) {
            Log.d(tag, "no control relay: hop TTL exhausted (ttl_in=$ttl)")
            return
        }
        val bucket = originBuckets.getOrPut(control.issuerPublicKeyHex) { TokenBucket(nowMs) }
        if (!bucket.tryTake(nowMs)) {
            Log.w(tag, "no control relay: issuer rate limit")
            return
        }
        val relayKey = "ctrl:${control.issuerNodeIdHex}:${control.ctrlMsgId}"
        copiesByMessage[relayKey] = (copiesByMessage[relayKey] ?: 0) + 1
        if (!relayScheduled.add(relayKey)) return
        val weakFirstDelay = ((rssi.coerceIn(-100, -35) + 35) * RSSI_DELAY_SPAN_MS / 65).toLong()
        scope.launch {
            delay(weakFirstDelay + (0..RELAY_JITTER_MS).random())
            val copies = copiesByMessage[relayKey] ?: 0
            relayScheduled.remove(relayKey)
            copiesByMessage.remove(relayKey)
            if (copies >= COUNTER_SUPPRESSION_K) {
                Log.d(tag, "no control relay: counter suppression copies=$copies")
            } else {
                // Relay under CONTROL_SERVICE_UUID — NOT the SOS broadcaster. The signed
                // 22 B payload + issuer pubkey + sig forward unchanged; only hop TTL shrinks.
                controlBroadcaster?.relay(control.payload, control.issuerPublicKey, control.signature, nextTtl)
            }
        }
    }

    /**
     * Pin the trust state for one of this device's own SOS frames. A terminal receipt
     * (backend-resolve 0x02 / self-cancel 0x03) is the local authority to STOP
     * advertising that SOS, sticky across reboots. A delivery (0x01) is informational
     * and must never overwrite an existing terminal row.
     */
    private suspend fun applyControlTrust(control: VerifiedControlFrame, nowMs: Long) {
        val existing = dao.getControlTrust(control.targetNodeIdHex, control.targetMsgId)
        val terminal = control.type == ControlType.BACKEND_RESOLVE || control.type == ControlType.SELF_CANCEL
        if (!terminal && existing != null && existing.terminal) {
            Log.d(tag, "delivery on terminal SOS ${control.targetNodeIdHex}:${control.targetMsgId}; no state change")
            return
        }
        dao.upsertControlTrust(
            ControlTrustEntity(
                nodeId = control.targetNodeIdHex,
                msgId = control.targetMsgId,
                terminal = terminal,
                resolveType = control.type.code,
                issuerNodeId = control.issuerNodeIdHex,
                authorityPubkeyHex = control.issuerPublicKeyHex,
                authoritySignatureHex = control.signatureHex,
                expiresAt = control.expiresAt * 1_000L,
                establishedAt = nowMs,
            ),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /**
     * Work items on the single consumer thread. [Sos] is a victim distress frame; the
     * [Control] variant carries the bare 119 B on-wire control blob (leading TTL intact)
     * so the consumer can both re-verify (verifyControlFrame strips the TTL) and re-relay.
     */
    private sealed interface Inbox {
        data class Sos(
            val payload22: ByteArray,
            val pub32: ByteArray,
            val sig64: ByteArray,
            val ttl: Int,
            val rssi: Int,
            val txPower: Int?,
            val primaryPhy: Int,
            val secondaryPhy: Int,
        ) : Inbox

        data class Control(val onWire119: ByteArray, val rssi: Int) : Inbox
    }

    private class TokenBucket(private var lastMs: Long, private var tokens: Double = ORIGIN_BURST.toDouble()) {
        fun tryTake(nowMs: Long): Boolean {
            tokens = minOf(ORIGIN_BURST.toDouble(), tokens + (nowMs - lastMs).coerceAtLeast(0) * ORIGIN_PER_MINUTE / 60_000.0)
            lastMs = nowMs
            if (tokens < 1) return false
            tokens -= 1
            return true
        }
    }

    companion object {
        const val MAX_TS_SKEW_SECONDS = 300L

        /** Queue enough bursts for mass nodes without unbounded coroutine growth. */
        const val FRAME_QUEUE_CAPACITY = 2_048

        /** Batch pruning keeps SQLite cheap during dense BLE storms. */
        const val PRUNE_EVERY_INSERTS = 128
        /** Do not re-broadcast after hearing this many valid copies during the timer. */
        const val COUNTER_SUPPRESSION_K = 3
        /** Weak RSSI wins geographically: -100 dBm starts near 0 ms, -35 near 1 s. */
        const val RSSI_DELAY_SPAN_MS = 1_000
        const val RELAY_JITTER_MS = 120
        const val ORIGIN_PER_MINUTE = 30
        const val ORIGIN_BURST = 8
    }
}
