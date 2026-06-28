package net.guacamaya.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeCatalogTest {

    @Test
    fun latestByNode_keepsOnePerId() {
        val id = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val older = stub(id, msgId = 1, receivedAt = 1000L)
        val newer = stub(id, msgId = 2, receivedAt = 5000L)
        val other = stub(byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d), msgId = 3, receivedAt = 3000L)
        val result = NodeCatalog.latestByNode(listOf(newer, older, other))
        assertEquals(2, result.size)
        assertEquals(5000L, result.first { it.nodeId.contentEquals(id) }.receivedAt)
    }

    @Test
    fun formatLastHeartbeat_recent() {
        val now = 1_700_000_000_000L
        assertEquals("ahora", NodeCatalog.formatLastHeartbeat(now - 5_000, now))
        assertEquals("hace 30s", NodeCatalog.formatLastHeartbeat(now - 30_000, now))
    }

    private fun stub(nodeId: ByteArray, msgId: Int, receivedAt: Long) = MessageEntity(
        nodeId = nodeId,
        msgId = msgId,
        tsUnix = 1_700_000_000L,
        latE7 = 0,
        lonE7 = 0,
        sosType = 7,
        critical = false,
        hasHeavy = false,
        hopTtl = 2,
        batteryBucket = 2,
        pubkey = ByteArray(32),
        payloadRaw = ByteArray(22),
        rssi = -60,
        receivedAt = receivedAt,
    )
}
