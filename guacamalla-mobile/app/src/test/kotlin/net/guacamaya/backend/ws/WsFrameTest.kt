package net.guacamaya.backend.ws

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class WsFrameTest {

    private fun roundTrip(text: String) {
        // Encode server-style (unmasked) and read it back.
        val frame = WsFrame.encode(WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8), mask = null)
        val msg = WsFrame.read(ByteArrayInputStream(frame))!!
        assertEquals(WsOpcode.TEXT, msg.opcode)
        assertEquals(text, msg.text())
    }

    @Test fun `text round trip across length boundaries`() {
        roundTrip("")                       // 0
        roundTrip("a".repeat(125))          // max 7-bit length
        roundTrip("b".repeat(126))          // first 16-bit length
        roundTrip("c".repeat(65_535))       // max 16-bit length
        roundTrip("d".repeat(65_536))       // first 64-bit length
    }

    @Test fun `subscribe message round trips`() {
        roundTrip("""{"type":"subscribe","channel":"solicito-ayuda"}""")
    }

    @Test fun `client text frame is masked and FIN with TEXT opcode`() {
        val frame = WsFrame.clientText("hi")
        assertEquals(0x81, frame[0].toInt() and 0xFF)      // FIN + TEXT
        assertTrue("mask bit set", (frame[1].toInt() and 0x80) != 0)
        assertEquals(2, frame[1].toInt() and 0x7F)          // payload length 2
        // header(2) + mask(4) + payload(2)
        assertEquals(8, frame.size)
    }

    @Test fun `masked client frame decodes back to original payload`() {
        val frame = WsFrame.clientText("héllo-世界")
        val msg = WsFrame.read(ByteArrayInputStream(frame))!!
        assertEquals("héllo-世界", msg.text())
    }

    @Test fun `clean EOF returns null`() {
        assertNull(WsFrame.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun `ping payload is preserved for pong echo`() {
        val ping = WsFrame.encode(WsOpcode.PING, byteArrayOf(1, 2, 3), mask = null)
        val msg = WsFrame.read(ByteArrayInputStream(ping))!!
        assertEquals(WsOpcode.PING, msg.opcode)
        assertArrayEquals(byteArrayOf(1, 2, 3), msg.payload)
    }

    @Test fun `two frames can be read sequentially from one stream`() {
        val a = WsFrame.encode(WsOpcode.TEXT, "one".toByteArray(), mask = null)
        val b = WsFrame.encode(WsOpcode.TEXT, "two".toByteArray(), mask = null)
        val input = ByteArrayInputStream(a + b)
        assertEquals("one", WsFrame.read(input)!!.text())
        assertEquals("two", WsFrame.read(input)!!.text())
        assertNull(WsFrame.read(input))
    }
}
