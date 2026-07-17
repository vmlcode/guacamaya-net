package net.guacamaya.proto

import net.guacamaya.crypto.Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FoundPayloadTest {

    private val targetNodeId = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    private fun payload() = FoundPayload(
        latE7 = 104_003_000,
        lonE7 = -669_000_000,
        tsUnix = 1_750_000_000L,
        targetNodeId = targetNodeId,
        targetMsgId = 4242,
        foundMsgId = 777,
    )

    @Test
    fun encodeUsesDocumentedBigEndianOffsets() {
        val bytes = payload().encode()
        assertEquals(FoundFrame.PAYLOAD_LEN, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(104_003_000, buf.getInt(0))
        assertEquals(-669_000_000, buf.getInt(4))
        assertEquals(1_750_000_000L, buf.getInt(8).toLong() and 0xFFFFFFFFL)
        assertArrayEquals(targetNodeId, bytes.copyOfRange(12, 16))
        assertEquals(4242, buf.getShort(16).toInt() and 0xFFFF)
        assertEquals(777, buf.getShort(18).toInt() and 0xFFFF)
        val crcStored = buf.getShort(20).toInt() and 0xFFFF
        assertEquals(Crc16.ccitt(bytes, 0, 20) and 0xFFFF, crcStored)
    }

    @Test
    fun decodeRoundTrips() {
        val decoded = FoundPayload.decode(payload().encode())
        assertEquals(payload(), decoded)
    }

    @Test
    fun buildFoundFrameSignsWithTheFoundDomain() {
        val sk = ByteArray(32) { 7 }
        val frame = buildFoundFrame(payload(), sk)
        assertEquals(FoundFrame.FRAME_LEN, frame.size)
        val payloadBytes = frame.copyOfRange(FoundFrame.PAYLOAD_OFF, FoundFrame.PAYLOAD_OFF + FoundFrame.PAYLOAD_LEN)
        val pubkey = frame.copyOfRange(FoundFrame.PUBKEY_OFF, FoundFrame.PUBKEY_OFF + FoundFrame.PUBKEY_LEN)
        val sig = frame.copyOfRange(FoundFrame.SIG_OFF, FoundFrame.SIG_OFF + FoundFrame.SIG_LEN)
        assertArrayEquals(Signer.derivePublicKey(sk), pubkey)
        // Signature covers the domain-prefixed bytes, not the bare payload.
        assertTrue(Signer.verifyMessage(pubkey, FoundFrame.signBytes(payloadBytes), sig))
        assertTrue(!Signer.verifyMessage(pubkey, payloadBytes, sig))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortTargetNodeId() {
        FoundPayload(0, 0, 0L, ByteArray(3), 1, 1)
    }
}
