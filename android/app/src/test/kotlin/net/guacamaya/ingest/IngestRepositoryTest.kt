package net.guacamaya.ingest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import net.guacamaya.mesh.MessageDao
import net.guacamaya.mesh.MessageEntity
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

        // Unused by the uploader.
        override suspend fun insert(entity: MessageEntity): Long = throw NotImplementedError()
        override fun observeRecent(limit: Int): Flow<List<MessageEntity>> = throw NotImplementedError()
        override suspend fun count(): Int = throw NotImplementedError()
        override fun observeCount(): Flow<Int> = throw NotImplementedError()
        override fun observeNodeCount(): Flow<Int> = throw NotImplementedError()
        override fun observeLatestPerNode(limit: Int): Flow<List<MessageEntity>> = throw NotImplementedError()
        override suspend fun pruneOldKeeping(keep: Int) = throw NotImplementedError()
        override suspend fun clear() = throw NotImplementedError()
    }

    private class FakeApi(
        private val result: IngestResult = IngestResult(ok = true, ingested = 1),
        private val failAfter: Int = Int.MAX_VALUE,
    ) : IngestApi {
        var calls = 0
        val sentSizes = mutableListOf<Int>()

        override suspend fun upload(frames: List<String>): IngestResult {
            calls++
            sentSizes += frames.size
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
}
