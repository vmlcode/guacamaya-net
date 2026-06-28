package net.guacamaya.ingest

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class IngestFrameTest {

    private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it + seed).toByte() }

    @Test fun `encode concatenates payload pubkey sig in order`() {
        val payload = bytes(22, 0)
        val pubkey = bytes(32, 100)
        val sig = bytes(64, 200)

        val frame = IngestFrame.encode(payload, pubkey, sig)

        assertEquals(IngestFrame.SIZE, frame.size)
        assertArrayEquals(payload, frame.copyOfRange(0, 22))
        assertArrayEquals(pubkey, frame.copyOfRange(22, 54))
        assertArrayEquals(sig, frame.copyOfRange(54, 118))
    }

    @Test fun `base64 round-trips back to the 118-byte frame`() {
        val payload = bytes(22, 1)
        val pubkey = bytes(32, 2)
        val sig = bytes(64, 3)

        val b64 = IngestFrame.toBase64(payload, pubkey, sig)
        val decoded = Base64.getDecoder().decode(b64)

        assertArrayEquals(IngestFrame.encode(payload, pubkey, sig), decoded)
        assertEquals(118, decoded.size)
    }

    @Test fun `wrong component sizes are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IngestFrame.encode(bytes(21, 0), bytes(32, 0), bytes(64, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IngestFrame.encode(bytes(22, 0), bytes(31, 0), bytes(64, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            IngestFrame.encode(bytes(22, 0), bytes(32, 0), bytes(63, 0))
        }
    }
}
