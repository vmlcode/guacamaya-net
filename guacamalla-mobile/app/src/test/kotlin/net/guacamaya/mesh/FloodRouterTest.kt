package net.guacamaya.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.guacamaya.crypto.Signer
import net.guacamaya.proto.Crc16
import net.guacamaya.proto.Flags
import net.guacamaya.proto.Payload
import net.guacamaya.proto.SosType
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.assertEquals
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
    }
}
