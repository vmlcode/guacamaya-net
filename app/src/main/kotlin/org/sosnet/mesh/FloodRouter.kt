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
import org.sosnet.mesh.MessageChannel
import java.security.MessageDigest

/**
 * Glues the BLE Observer to the rest of the mesh. Implements the reject cascade
 * from docs/crypto.md, cheapest first:
 *
 *  1. SHA-256(pubkey)[0..4] == payload.node_id (else: pubkey swap attack)
 *  2. Payload.decode (CRC check)
 *  3. ts_unix skew ≤ 300 s (replay defense)
 *  4. hop_ttl > 0 after decrement
 *  5. Ed25519 verify
 *
 * On pass: dedupe → persist → enqueue rebroadcast (via [Broadcaster.swap]).
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
    fun onFrame(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, rssi: Int) {
        scope.launch { handle(payload22, pub32, sig64, rssi) }
    }

    private suspend fun handle(payload22: ByteArray, pub32: ByteArray, sig64: ByteArray, rssi: Int) {
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

        // 4. Hop TTL.
        if (payload.flags.hopTtl <= 0) {
            Log.d(tag, "DROP: hop_ttl exhausted")
            return
        }

        // 5. Signature.
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
            channel = MessageChannel.SOS,
        )
        dao.insert(entity)
        Log.i(tag, "OK  node=${payload.nodeId.toHex()} msg=${payload.msgId} type=${payload.sosType} rssi=$rssi fresh=$fresh hops_left=${payload.flags.hopTtl}")

        if (!fresh) return  // already flooded

        // 7. Rebroadcast the EXACT 118 B we received. v1 keeps the origin's
        //    signature intact (re-signing with our key would break next-hop
        //    verify, since node_id is bound to the origin's pubkey). Loop
        //    prevention is the dedupe cache's job — each node re-emits a given
        //    (node_id, msg_id) at most once per TTL window. hop_ttl is metadata
        //    in v1; P11 introduces a wrapper frame for true hop-by-hop decrement.
        broadcaster ?: return
        broadcaster.swap(payload22, pub32, sig64)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_TS_SKEW_SECONDS = 300L
    }
}
