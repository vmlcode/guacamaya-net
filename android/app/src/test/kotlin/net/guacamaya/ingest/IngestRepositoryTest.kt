package net.guacamaya.ingest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import net.guacamaya.mesh.MessageDao
import net.guacamaya.mesh.MessageEntity
import net.guacamaya.mesh.NeighborEstimateEntity
import net.guacamaya.mesh.ControlReplayEntity
import net.guacamaya.mesh.ControlTrustEntity
import net.guacamaya.mesh.OutboundControlEntity
import net.guacamaya.mesh.OutboundFoundEntity
import net.guacamaya.mesh.NeighborObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IngestRepositoryTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    /** In-memory MessageDao implementing only what the uploader touches. */
    private class FakeDao(seed: List<MessageEntity>) : MessageDao {
        val rows = seed.toMutableList()

        override suspend fun selectUploadable(limit: Int): List<MessageEntity> =
            rows.filter { !it.uploaded && it.sig.size == 64 }
                .sortedBy { it.receivedAt }
                .take(limit)

        override suspend fun markUploaded(ids: List<Long>) {
            val set = ids.toHashSet()
            for (i in rows.indices) {
                if (rows[i].id in set) rows[i] = rows[i].copy(uploaded = true)
            }
        }

        override suspend fun countUploadable(): Int =
            rows.count { !it.uploaded && it.sig.size == 64 }

        // ── Outbound control frames: mule-upload queue (self-cancel → backend) ───────
        val controlRows = mutableListOf<OutboundControlEntity>()

        override suspend fun insertOutboundControl(entity: OutboundControlEntity): Long {
            if (controlRows.any { it.signatureHex == entity.signatureHex }) return -1L
            controlRows += entity
            return 1L
        }

        override suspend fun selectUploadableControl(limit: Int): List<OutboundControlEntity> =
            controlRows.filter { !it.uploaded }.sortedBy { it.queuedAt }.take(limit)

        override suspend fun markControlUploaded(sigs: List<String>) {
            val set = sigs.toHashSet()
            for (i in controlRows.indices) {
                if (controlRows[i].signatureHex in set) {
                    controlRows[i] = controlRows[i].copy(uploaded = true)
                }
            }
        }

        override suspend fun countUploadableControl(): Int = controlRows.count { !it.uploaded }

        // Unused by the uploader.
        override suspend fun insert(entity: MessageEntity): Long = throw NotImplementedError()
        override fun observeRecent(limit: Int): Flow<List<MessageEntity>> = throw NotImplementedError()
        override suspend fun count(): Int = throw NotImplementedError()
        override fun observeCount(): Flow<Int> = throw NotImplementedError()
        override fun observeNodeCount(): Flow<Int> = throw NotImplementedError()
        override fun observeLatestPerNode(limit: Int): Flow<List<MessageEntity>> = throw NotImplementedError()
        override suspend fun latestHelpFramesPerNode(limit: Int): List<MessageEntity> = throw NotImplementedError()
        override suspend fun pruneOldKeeping(keep: Int) = throw NotImplementedError()
        override suspend fun clear() = throw NotImplementedError()
        override suspend fun insertNeighborObservation(entity: NeighborObservationEntity): Long = throw NotImplementedError()
        override suspend fun pruneNeighborObservations(nodeId: String, keep: Int) = throw NotImplementedError()
        override suspend fun upsertNeighborEstimate(entity: NeighborEstimateEntity) = throw NotImplementedError()
        override fun observeNeighborEstimates(limit: Int): Flow<List<NeighborEstimateEntity>> = throw NotImplementedError()
        override suspend fun refreshNeighborStates(now: Long, degradedAfter: Long, lostAfter: Long) = throw NotImplementedError()

        // ── Control frames: not touched by the uploader. ──────────────────────
        override suspend fun insertControlReplay(entity: ControlReplayEntity): Long = throw NotImplementedError()
        override suspend fun hasControlReplay(signatureHex: String): Boolean = throw NotImplementedError()
        override suspend fun pruneControlReplay(now: Long): Int = throw NotImplementedError()
        override suspend fun countControlReplay(): Int = throw NotImplementedError()
        override suspend fun upsertControlTrust(entity: ControlTrustEntity) = throw NotImplementedError()
        override suspend fun getControlTrust(nodeId: String, msgId: Int): ControlTrustEntity? = throw NotImplementedError()
        override fun observeControlTrust(nodeId: String, msgId: Int): Flow<ControlTrustEntity?> = throw NotImplementedError()
        override suspend fun terminalControlTrusts(): List<ControlTrustEntity> = throw NotImplementedError()
        override suspend fun countControlTrust(): Int = throw NotImplementedError()
        override suspend fun clearControlTrust() = throw NotImplementedError()

        // ── Outbound found frames: mule-upload queue ("Encontrado" → backend) ────────
        val outboundFound = mutableListOf<OutboundFoundEntity>()

        override suspend fun insertOutboundFound(entity: OutboundFoundEntity): Long {
            if (outboundFound.any { it.signatureHex == entity.signatureHex }) return -1
            outboundFound.add(entity)
            return outboundFound.size.toLong()
        }

        override suspend fun selectUploadableFound(limit: Int): List<OutboundFoundEntity> =
            outboundFound.filter { !it.uploaded }.sortedBy { it.queuedAt }.take(limit)

        override suspend fun markFoundUploaded(sigs: List<String>) {
            val marked = outboundFound.map { if (it.signatureHex in sigs) it.copy(uploaded = true) else it }
            outboundFound.clear()
            outboundFound.addAll(marked)
        }

        override suspend fun countUploadableFound(): Int = outboundFound.count { !it.uploaded }
    }

    private class FakeApi(
        private val result: IngestResult = IngestResult(ok = true, ingested = 1),
        private val failAfter: Int = Int.MAX_VALUE,
    ) : IngestApi {
        var calls = 0
        val sentSizes = mutableListOf<Int>()
        val controlSentSizes = mutableListOf<Int>()
        val uploadedFound = mutableListOf<List<String>>()

        override suspend fun upload(
            frames: List<String>,
            controlFrames: List<String>,
            foundFrames: List<String>,
        ): IngestResult {
            calls++
            sentSizes += frames.size
            controlSentSizes += controlFrames.size
            uploadedFound += foundFrames
            if (calls > failAfter) throw IOException("boom")
            return result
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun entity(
        id: Long,
        receivedAt: Long = id,
        sigLen: Int = 64,
        uploaded: Boolean = false,
    ) = MessageEntity(
        id = id,
        nodeId = ByteArray(4) { id.toByte() },
        msgId = id.toInt(),
        tsUnix = 1_750_000_000L,
        latE7 = 19_000_000,
        lonE7 = -99_000_000,
        sosType = 1,
        critical = true,
        hasHeavy = false,
        hopTtl = 15,
        batteryBucket = 3,
        pubkey = ByteArray(32) { id.toByte() },
        payloadRaw = ByteArray(22) { id.toByte() },
        sig = ByteArray(sigLen) { id.toByte() },
        rssi = -50,
        receivedAt = receivedAt,
        uploaded = uploaded,
    )

    private fun ctrl(sig: String, queuedAt: Long) = OutboundControlEntity(
        signatureHex = sig,
        frameType = 3,
        frame = ByteArray(118) { 0xAB.toByte() },
        uploaded = false,
        queuedAt = queuedAt,
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test fun `uploads all pending across batches and marks them`() = runBlocking {
        val dao = FakeDao((1L..5L).map { entity(it) })
        val api = FakeApi(IngestResult(ok = true, ingested = 1, duplicate = 0))
        val repo = IngestRepository(dao, api, batchSize = 2)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(5, summary.framesSent)
        assertEquals(3, summary.batches)          // 2 + 2 + 1
        assertEquals(listOf(2, 2, 1), api.sentSizes)
        assertTrue(dao.rows.all { it.uploaded })
        assertEquals(0, dao.countUploadable())
    }

    @Test fun `skips pre-migration empty-sig rows and already-uploaded rows`() = runBlocking {
        val dao = FakeDao(
            listOf(
                entity(1L),                         // eligible
                entity(2L, sigLen = 0),             // pre-v3, no signature
                entity(3L, uploaded = true),        // already sent
                entity(4L),                         // eligible
            )
        )
        val api = FakeApi()
        val repo = IngestRepository(dao, api, batchSize = 50)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(2, summary.framesSent)
        assertFalse(dao.rows.first { it.id == 2L }.uploaded)  // never sent
        assertTrue(dao.rows.first { it.id == 1L }.uploaded)
        assertTrue(dao.rows.first { it.id == 4L }.uploaded)
    }

    @Test fun `transport failure returns RETRY and keeps earlier batches marked`() = runBlocking {
        val dao = FakeDao((1L..4L).map { entity(it) })
        val api = FakeApi(failAfter = 1)            // first batch ok, second throws
        val repo = IngestRepository(dao, api, batchSize = 2)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.RETRY, summary.outcome)
        assertEquals(2, summary.framesSent)         // only the first batch counted
        assertEquals(2, dao.rows.count { it.uploaded })
        assertEquals(2, dao.countUploadable())      // the failed batch stays pending
    }

    @Test fun `nothing to upload is success with zero batches`() = runBlocking {
        val dao = FakeDao(emptyList())
        val api = FakeApi()
        val repo = IngestRepository(dao, api)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(0, summary.batches)
        assertEquals(0, api.calls)
    }

    @Test fun `rejected count is surfaced not retried`() = runBlocking {
        val dao = FakeDao(listOf(entity(1L)))
        val api = FakeApi(IngestResult(ok = true, ingested = 0, duplicate = 0, rejected = 1))
        val repo = IngestRepository(dao, api, batchSize = 50)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(1, summary.rejected)
        assertTrue(dao.rows.first().uploaded)       // marked despite rejection — not retried forever
    }

    @Test fun `uploads queued self-cancel control frames and marks them`() = runBlocking {
        val dao = FakeDao(emptyList())
        dao.controlRows += listOf(ctrl("aa", 1), ctrl("bb", 2))
        val api = FakeApi(IngestResult(ok = true, ingested = 0, controlAccepted = 2))
        val repo = IngestRepository(dao, api, batchSize = 50)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(2, summary.controlFramesSent)
        assertEquals(1, summary.batches)
        assertEquals(0, summary.framesSent)             // no SOS frames in this run
        assertEquals(listOf(2), api.controlSentSizes)
        assertTrue(dao.controlRows.all { it.uploaded })
        assertEquals(0, dao.countUploadableControl())
    }

    @Test fun `surfaces backend-minted control frames for re-injection`() = runBlocking {
        val dao = FakeDao(listOf(entity(1L)))
        val api = FakeApi(
            IngestResult(ok = true, ingested = 1, controlFrames = listOf("Zm9v", "YmFy")),
        )
        val repo = IngestRepository(dao, api, batchSize = 50)

        val summary = repo.uploadPending()

        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(listOf("Zm9v", "YmFy"), summary.mintedControlFrames)
    }

    @Test fun `control-only body still accepted and not treated as empty`() = runBlocking {
        val dao = FakeDao(emptyList())
        dao.controlRows += listOf(ctrl("cc", 1))
        val api = FakeApi(IngestResult(ok = true, ingested = 0, controlAccepted = 1))
        val repo = IngestRepository(dao, api, batchSize = 10)

        val summary = repo.uploadPending()

        // No SOS frames, one control frame → exactly one POST carrying controlFrames only.
        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(1, api.calls)
        assertEquals(listOf(0), api.sentSizes)
        assertEquals(listOf(1), api.controlSentSizes)
    }

    @Test
    fun `drains found queue and marks uploaded`() = runBlocking {
        // Arrange: one pending found frame, no SOS/control work.
        val dao = FakeDao(emptyList())
        dao.outboundFound.add(
            OutboundFoundEntity(
                signatureHex = "ab".repeat(64),
                frame = ByteArray(118) { 1 },
                queuedAt = 1L,
            )
        )
        val api = FakeApi()
        val repo = IngestRepository(dao, api, batchSize = 50)

        val summary = repo.uploadPending()
        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(1, summary.foundFramesSent)
        assertEquals(0, dao.countUploadableFound())
    }
}
