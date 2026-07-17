package net.guacamaya.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.guacamaya.ble.ControlRelay
import net.guacamaya.ble.Observer
import net.guacamaya.crypto.Signer
import net.guacamaya.proto.ControlFrame
import net.guacamaya.proto.ControlPayload
import net.guacamaya.proto.ControlType
import net.guacamaya.proto.Crc16
import net.guacamaya.proto.Flags
import net.guacamaya.proto.Payload
import net.guacamaya.proto.SosType
import net.guacamaya.proto.buildControlFrame
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Unit tests for the FloodRouter reject cascade:
 *   1. pubkey/node_id binding
 *   2. CRC check
 *   3. Age policy (presence vs help-request windows)
 *   4. Ed25519 signature verification
 *   → dedupe → persist → relay
 *
 * All tests run on the JVM (no Android runtime). Android stubs return default
 * values via unitTests.isReturnDefaultValues = true (gradle.properties), so
 * Log.w/d/i calls are no-ops. The broadcaster is injected as null — relay
 * behavior requires instrumented tests with real BLE hardware.
 *
 * NOTE: FloodRouter spawns a perpetual Channel consumer in its `init` block. Two
 * things make that testable:
 *   - it is constructed with [TestScope.backgroundScope] (not `this`), which runTest
 *     cancels at the end of the body — using `this` leaves the consumer
 *     forever-suspended and fails every test with UncompletedCoroutinesError;
 *   - the tests run on [UnconfinedTestDispatcher] so the consumer starts eagerly and
 *     each onFrame() send is drained synchronously before the assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FloodRouterTest {

    // Fixed clock: 1,800,000,000 unix seconds — well within uint32 range.
    private val nowMs = 1_800_000_000_000L
    private val nowSec = nowMs / 1000

    private val rng = SecureRandom()
    private lateinit var priv32: ByteArray
    private lateinit var pub32: ByteArray
    private lateinit var nodeId: ByteArray   // SHA-256(pub32)[0..4]
    private lateinit var dao: FakeMessageDao

    @Before fun setUp() {
        val gen = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(rng))
        }
        val kp = gen.generateKeyPair()
        priv32 = (kp.private as Ed25519PrivateKeyParameters).encoded
        pub32  = (kp.public  as Ed25519PublicKeyParameters).encoded
        nodeId = MessageDigest.getInstance("SHA-256").digest(pub32).copyOfRange(0, 4)
        dao = FakeMessageDao()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildPayload(
        tsUnix: Long   = nowSec,
        critical: Boolean = true,
        sosType: SosType  = SosType.DISTRESS,
        msgId: Int     = 1,
        hopTtl: Int    = 5,
    ) = Payload(
        latE7 = 10_000_000, lonE7 = -74_000_000,
        tsUnix = tsUnix, nodeId = nodeId,
        flags = Flags(hasHeavy = false, critical = critical, batteryBucket = 2, hopTtl = hopTtl),
        sosType = sosType, msgId = msgId,
    )

    private fun sign(payload: Payload): Triple<ByteArray, ByteArray, ByteArray> {
        val p22 = payload.encode()
        return Triple(p22, pub32, Signer.sign(priv32, p22))
    }

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(rng)) }
        val kp = gen.generateKeyPair()
        return (kp.private as Ed25519PrivateKeyParameters).encoded to
               (kp.public  as Ed25519PublicKeyParameters).encoded
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test fun `valid frame is persisted`() = runTest(UnconfinedTestDispatcher()) {
        val (p22, pub, sig) = sign(buildPayload())
        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(1, dao.inserted.size)
    }

    @Test fun `persisted entity carries correct fields`() = runTest(UnconfinedTestDispatcher()) {
        val payload = buildPayload(msgId = 42)
        val (p22, pub, sig) = sign(payload)
        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 3, rssi = -80)
        advanceUntilIdle()
        val e = dao.inserted.single()
        assertTrue(e.nodeId.contentEquals(nodeId))
        assertEquals(42, e.msgId)
        assertEquals(-80, e.rssi)
        assertEquals(nowMs, e.receivedAt)
    }

    // ── cascade step 1: pubkey / node_id binding ──────────────────────────────

    @Test fun `pubkey-nodeId mismatch is dropped`() = runTest(UnconfinedTestDispatcher()) {
        // A different keypair whose SHA-256 doesn't match the payload's node_id.
        val (otherPriv, otherPub) = generateKeyPair()
        val p22 = buildPayload().encode()
        val sig = Signer.sign(otherPriv, p22)   // signed with other key to avoid masking

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, otherPub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    // ── cascade step 2: CRC ───────────────────────────────────────────────────

    @Test fun `bad CRC is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val (p22, pub, sig) = sign(buildPayload())
        val corrupted = p22.copyOf().also { it[20] = (it[20].toInt() xor 0xFF).toByte() }

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(corrupted, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    // ── cascade step 3: age policy ─────────────────────────────────────────────

    @Test fun `stale presence frame is dropped`() = runTest(UnconfinedTestDispatcher()) {
        // Presence = non-critical OTHER (see Payload.isHelpRequest)
        val staleTs = nowSec - AgePolicy.PRESENCE_MAX_AGE_SECONDS - 1
        val (p22, pub, sig) = sign(buildPayload(tsUnix = staleTs, critical = false, sosType = SosType.OTHER))

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    @Test fun `help request inside 24h window is accepted`() = runTest(UnconfinedTestDispatcher()) {
        val oldTs = nowSec - 23 * 3_600L
        val (p22, pub, sig) = sign(buildPayload(tsUnix = oldTs, critical = true))

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(1, dao.inserted.size)
    }

    @Test fun `help request older than 24h is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val tooOld = nowSec - AgePolicy.HELP_MAX_AGE_SECONDS - 1
        val (p22, pub, sig) = sign(buildPayload(tsUnix = tooOld, critical = true))

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    @Test fun `far-future frame beyond skew tolerance is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val futureTs = nowSec + AgePolicy.FUTURE_SKEW_SECONDS + 1
        val (p22, pub, sig) = sign(buildPayload(tsUnix = futureTs))

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    // ── cascade step 4: Ed25519 signature ─────────────────────────────────────

    @Test fun `payload tampered after signing is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val (p22, pub, sig) = sign(buildPayload())
        // Flip a bit in the latE7 field (bytes 0..3, not node_id or CRC)
        // and recompute the CRC so the frame passes step 2 but fails step 4.
        val tampered = p22.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        val crc = Crc16.ccitt(tampered, 0, 20)
        tampered[20] = (crc shr 8).toByte()
        tampered[21] = (crc and 0xFF).toByte()

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(tampered, pub, sig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    @Test fun `signature byte flip is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val (p22, pub, sig) = sign(buildPayload())
        val badSig = sig.copyOf().also { it[0] = (it[0].toInt() xor 0x80).toByte() }

        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, badSig, ttl = 5, rssi = -70)
        advanceUntilIdle()
        assertEquals(0, dao.inserted.size)
    }

    // ── dedupe ────────────────────────────────────────────────────────────────

    @Test fun `duplicate frame within TTL window is persisted once`() = runTest(UnconfinedTestDispatcher()) {
        val (p22, pub, sig) = sign(buildPayload())
        val router = FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
        router.onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        router.onFrame(p22, pub, sig, ttl = 5, rssi = -65)
        advanceUntilIdle()
        assertEquals(1, dao.inserted.size)
    }

    // ── hop TTL ───────────────────────────────────────────────────────────────

    @Test fun `frame with TTL 1 is still persisted`() = runTest(UnconfinedTestDispatcher()) {
        // nextTtl = 0 suppresses relay but must not suppress persistence.
        val (p22, pub, sig) = sign(buildPayload(hopTtl = 1))
        FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
            .onFrame(p22, pub, sig, ttl = 1, rssi = -70)
        advanceUntilIdle()
        assertEquals(1, dao.inserted.size)
    }

    @Test fun `only an origin TTL frame becomes a neighbour observation`() = runTest(UnconfinedTestDispatcher()) {
        val observations = mutableListOf<DirectRadioObservation>()
        val recorder = NeighborObservationSink { observations += it }
        val (p22, pub, sig) = sign(buildPayload(hopTtl = 5))
        val router = FloodRouter(
            dao = dao,
            observationRecorder = recorder,
            scope = backgroundScope,
            broadcaster = null,
            now = { nowMs },
        )

        router.onFrame(p22, pub, sig, ttl = 4, rssi = -48) // authentic relay, not origin
        router.onFrame(p22, pub, sig, ttl = 5, rssi = -72) // direct origin
        advanceUntilIdle()

        assertEquals(1, observations.size)
        assertEquals(-72, observations.single().rssi)
        assertTrue(observations.single().nodeId.contentEquals(nodeId))
    }

    // ── multi-frame ───────────────────────────────────────────────────────────

    @Test fun `distinct messages from same node are each persisted`() = runTest(UnconfinedTestDispatcher()) {
        val router = FloodRouter(dao = dao, scope = backgroundScope, broadcaster = null, now = { nowMs })
        for (id in 1..5) {
            val (p22, pub, sig) = sign(buildPayload(msgId = id))
            router.onFrame(p22, pub, sig, ttl = 5, rssi = -70)
        }
        advanceUntilIdle()
        assertEquals(5, dao.inserted.size)
    }

    // ── control frames (Phase 4) ──────────────────────────────────────────────

    /** Victim node id under test = SHA-256(this keypair)[0..3]. */
    private val victimNodeIdHex: String get() = ControlFrame.toHex(ControlFrame.nodeIdOf(pub32))

    /** Prepend the leading mutable hop-TTL byte to a 118 B upload frame → 119 B on-wire. */
    private fun controlOnWire(frame118: ByteArray, ttl: Int = 15): ByteArray =
        ByteArray(ControlFrame.ON_WIRE_LEN).also {
            it[0] = ttl.toByte()
            System.arraycopy(frame118, 0, it, 1, ControlFrame.FRAME_LEN)
        }

    /** Self-cancel (0x03) signed by the victim itself → issuer node == target node. */
    private fun selfCancelFrame(ctrlMsgId: Int = 1, targetMsgId: Int = 42): ByteArray {
        val payload = ControlPayload(
            type = ControlType.SELF_CANCEL,
            targetNodeId = ControlFrame.nodeIdOf(pub32),
            targetMsgId = targetMsgId,
            issuedAt = nowSec,
            expiresAt = nowSec + 3_600,
            ctrlMsgId = ctrlMsgId,
        )
        return buildControlFrame(payload, priv32)
    }

    /** Delivery (0x01) or backend-resolve (0x02) signed by the pinned backend key. */
    private fun backendFrame(
        type: ControlType,
        backendPriv: ByteArray,
        ctrlMsgId: Int = 1,
        targetMsgId: Int = 42,
    ): ByteArray {
        val payload = ControlPayload(
            type = type,
            targetNodeId = ControlFrame.nodeIdOf(pub32),
            targetMsgId = targetMsgId,
            issuedAt = nowSec,
            expiresAt = nowSec + 3_600,
            ctrlMsgId = ctrlMsgId,
        )
        return buildControlFrame(payload, backendPriv)
    }

    private fun radio(rssi: Int = -70) = Observer.RadioMetadata(rssi, null, 0, 0)

    @Test fun `duplicate control frame is acted on once and relayed once`() =
        runTest(UnconfinedTestDispatcher()) {
            val relays = mutableListOf<Int>()
            val relay = ControlRelay { _, _, _, ttl -> relays.add(ttl) }
            val router = FloodRouter(
                dao = dao,
                scope = backgroundScope,
                controlBroadcaster = relay,
                now = { nowMs },
            )
            val onWire = controlOnWire(selfCancelFrame())
            router.onControlFrame(onWire, radio())        // first — acted on + relayed
            router.onControlFrame(onWire, radio(-65))     // same signature — IGNOREd
            advanceUntilIdle()

            assertEquals(1, dao.countControlReplay())     // persistent gate deduped the 2nd
            assertEquals(1, relays.size)                  // replay never re-relays
            assertEquals(14, relays.single())             // ttl-1
            val trust = dao.getControlTrust(victimNodeIdHex, 42)
            assertNotNull(trust)
            assertTrue(trust!!.terminal)                  // self-cancel is terminal
        }

    @Test fun `delivery does not overwrite a terminal resolve`() =
        runTest(UnconfinedTestDispatcher()) {
            val (bePriv, _) = generateKeyPair()
            val bePubHex = ControlFrame.toHex(Signer.derivePublicKey(bePriv))
            val router = FloodRouter(
                dao = dao,
                scope = backgroundScope,
                backendPublicKeyProvider = { bePubHex },
                now = { nowMs },
            )
            router.onControlFrame(controlOnWire(backendFrame(ControlType.BACKEND_RESOLVE, bePriv)), radio())
            advanceUntilIdle()
            // Distinct ctrlMsgId → fresh signature (not a replay), same target SOS.
            router.onControlFrame(controlOnWire(backendFrame(ControlType.DELIVERY, bePriv, ctrlMsgId = 2)), radio())
            advanceUntilIdle()

            val trust = dao.getControlTrust(victimNodeIdHex, 42)!!
            assertTrue(trust.terminal)                              // sticky terminal
            assertEquals(ControlType.BACKEND_RESOLVE.code, trust.resolveType) // not clobbered by delivery
        }

    @Test fun `control frame at hop edge is acted on but not relayed`() =
        runTest(UnconfinedTestDispatcher()) {
            val relays = mutableListOf<Int>()
            val relay = ControlRelay { _, _, _, _ -> relays.add(0) }
            val router = FloodRouter(
                dao = dao,
                scope = backgroundScope,
                controlBroadcaster = relay,
                now = { nowMs },
            )
            router.onControlFrame(controlOnWire(selfCancelFrame(), ttl = 1), radio()) // nextTtl = 0
            advanceUntilIdle()

            assertEquals(1, dao.countControlReplay())    // still verified + persisted
            assertEquals(0, relays.size)                 // TTL exhausted → no relay
        }

    @Test fun `self-cancel from a non-target key is rejected and not acted on`() =
        runTest(UnconfinedTestDispatcher()) {
            val (otherPriv, _) = generateKeyPair()
            val payload = ControlPayload(
                type = ControlType.SELF_CANCEL,
                targetNodeId = ControlFrame.nodeIdOf(pub32), // victim
                targetMsgId = 42,
                issuedAt = nowSec,
                expiresAt = nowSec + 3_600,
                ctrlMsgId = 1,
            )
            val frame = buildControlFrame(payload, otherPriv) // signed by a non-victim key
            val router = FloodRouter(dao = dao, scope = backgroundScope, now = { nowMs })
            router.onControlFrame(controlOnWire(frame), radio())
            advanceUntilIdle()

            assertEquals(0, dao.countControlReplay())    // authz (self-binding) failed → drop
            assertEquals(0, dao.countControlTrust())
        }

    @Test fun `delivery without a pinned backend key is rejected`() =
        runTest(UnconfinedTestDispatcher()) {
            val (bePriv, _) = generateKeyPair()
            val router = FloodRouter(
                dao = dao,
                scope = backgroundScope,
                backendPublicKeyProvider = { null }, // nothing pinned
                now = { nowMs },
            )
            router.onControlFrame(controlOnWire(backendFrame(ControlType.DELIVERY, bePriv)), radio())
            advanceUntilIdle()

            assertEquals(0, dao.countControlReplay())    // backend-pin authz failed → drop
        }

    @Test fun `malformed control envelope is dropped`() =
        runTest(UnconfinedTestDispatcher()) {
            val router = FloodRouter(dao = dao, scope = backgroundScope, now = { nowMs })
            router.onControlFrame(ByteArray(50), radio()) // neither 118 nor 119 → size reject
            advanceUntilIdle()

            assertEquals(0, dao.countControlReplay())
        }

    // ── fake DAO ──────────────────────────────────────────────────────────────

    private class FakeMessageDao : MessageDao {
        val inserted = mutableListOf<MessageEntity>()
        private val keys = mutableSetOf<Pair<List<Byte>, Int>>()

        override suspend fun insert(entity: MessageEntity): Long {
            val key = entity.nodeId.toList() to entity.msgId
            return if (!keys.add(key)) -1L
            else { inserted.add(entity); inserted.size.toLong() }
        }

        override suspend fun pruneOldKeeping(keep: Int) {}
        override suspend fun clear() { inserted.clear() }

        // Unused by FloodRouter — fail loudly if unexpectedly called.
        override fun observeRecent(limit: Int): Flow<List<MessageEntity>> = flowOf(emptyList())
        override suspend fun count(): Int = throw NotImplementedError()
        override fun observeCount(): Flow<Int> = throw NotImplementedError()
        override fun observeNodeCount(): Flow<Int> = throw NotImplementedError()
        override fun observeLatestPerNode(limit: Int): Flow<List<MessageEntity>> = throw NotImplementedError()
        override suspend fun latestHelpFramesPerNode(limit: Int): List<MessageEntity> = throw NotImplementedError()
        override suspend fun selectUploadable(limit: Int): List<MessageEntity> = throw NotImplementedError()
        override suspend fun markUploaded(ids: List<Long>) = throw NotImplementedError()
        override suspend fun countUploadable(): Int = throw NotImplementedError()
        override suspend fun insertNeighborObservation(entity: NeighborObservationEntity): Long = throw NotImplementedError()
        override suspend fun pruneNeighborObservations(nodeId: String, keep: Int) = throw NotImplementedError()
        override suspend fun upsertNeighborEstimate(entity: NeighborEstimateEntity) = throw NotImplementedError()
        override fun observeNeighborEstimates(limit: Int): Flow<List<NeighborEstimateEntity>> = throw NotImplementedError()
        override suspend fun refreshNeighborStates(now: Long, degradedAfter: Long, lostAfter: Long) = throw NotImplementedError()

        // ── Control frames: in-memory replay guard + trust state. Reused by the
        // ControlRouter tests (Phase 4). Mirrors Room semantics: IGNORE→-1 on replay,
        // REPLACE for trust upsert. ───────────────────────────────────────────────
        private val controlReplay = linkedMapOf<String, ControlReplayEntity>()
        private val controlTrust = mutableMapOf<Pair<String, Int>, ControlTrustEntity>()

        override suspend fun insertControlReplay(entity: ControlReplayEntity): Long =
            if (entity.signatureHex in controlReplay) -1L
            else { controlReplay[entity.signatureHex] = entity; controlReplay.size.toLong() }

        override suspend fun hasControlReplay(signatureHex: String): Boolean =
            signatureHex in controlReplay

        override suspend fun pruneControlReplay(now: Long): Int {
            val stale = controlReplay.values.filter { it.expiresAt < now }
            stale.forEach { controlReplay.remove(it.signatureHex) }
            return stale.size
        }

        override suspend fun countControlReplay(): Int = controlReplay.size

        override suspend fun upsertControlTrust(entity: ControlTrustEntity) {
            controlTrust[entity.nodeId to entity.msgId] = entity
        }

        override suspend fun getControlTrust(nodeId: String, msgId: Int): ControlTrustEntity? =
            controlTrust[nodeId to msgId]

        override fun observeControlTrust(nodeId: String, msgId: Int): Flow<ControlTrustEntity?> =
            flowOf(controlTrust[nodeId to msgId])

        override suspend fun terminalControlTrusts(): List<ControlTrustEntity> =
            controlTrust.values.filter { it.terminal }

        override suspend fun countControlTrust(): Int = controlTrust.size

        override suspend fun clearControlTrust() = controlTrust.clear()

        // ── Outbound control queue: uploader-only; routing tests ignore it. ───────
        override suspend fun insertOutboundControl(entity: OutboundControlEntity): Long = 1L
        override suspend fun selectUploadableControl(limit: Int): List<OutboundControlEntity> = emptyList()
        override suspend fun markControlUploaded(sigs: List<String>) {}
        override suspend fun countUploadableControl(): Int = 0
    }
}
