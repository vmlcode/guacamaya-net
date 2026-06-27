package org.sosnet.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.sosnet.ble.Broadcaster
import org.sosnet.crypto.Signer
import org.sosnet.proto.Flags
import org.sosnet.proto.Payload
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
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val tag = "sosnet.mesh.FloodRouter"

    /** Callback for the BLE Observer. Cheap — offloads to a coroutine. */
    fun onFrame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int, rssi: Int) {
        scope.launch { handle(payload22, pub32, sig64, ttl, rssi) }
    }

    private suspend fun handle(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, ttl: Int, rssi: Int) {
        // 1. Pubkey binding.
        val digest = MessageDigest.getInstance("SHA-256").digest(pub32)
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

        // 3. Replay window.
        val nowSec = now() / 1000
        val skew = Math.abs(payload.tsUnix - nowSec)
        if (skew > MAX_TS_SKEW_SECONDS) {
            Log.w(tag, "DROP: ts skew=${skew}s > $MAX_TS_SKEW_SECONDS")
            return
        }

        // 4. Signature (the expensive gate — last).
        if (!Signer.verify(pub32, payload22, sig64)) {
            Log.w(tag, "DROP: signature invalid")
            return
        }

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
            rssi = rssi,
            receivedAt = now(),
        )
        dao.insert(entity)
        dao.pruneOldKeeping(MAX_STORED_MESSAGES)
        Log.i(tag, "OK  node=${payload.nodeId.toHex()} msg=${payload.msgId} type=${payload.sosType} rssi=$rssi fresh=$fresh ttl_in=$ttl")

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
        broadcaster?.swap(payload22, pub32, sig64, nextTtl)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_TS_SKEW_SECONDS = 300L

        /** Cap on rows kept in the message store; older rows pruned after each insert. */
        const val MAX_STORED_MESSAGES = 500
    }
}
