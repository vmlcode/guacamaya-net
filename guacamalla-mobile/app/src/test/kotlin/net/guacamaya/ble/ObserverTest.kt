package net.guacamaya.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ObserverTest {

    private val serviceUuid = UUID.fromString("8d3d0001-2a1b-4c8e-9c0f-1234567890ab")
    private val frameSize = 119

    @Test
    fun uuidToLeBytes_matchesBleAdEncoding() {
        val le = Observer.uuidToLeBytes(serviceUuid)
        assertEquals(16, le.size)
        assertArrayEquals(
            byteArrayOf(
                0x01, 0x00, 0x3d, 0x8d.toByte(),
                0x1b, 0x2a, 0x8e.toByte(), 0x4c.toByte(),
                0x9c.toByte(), 0x0f, 0x12, 0x34, 0x56, 0x78.toByte(), 0x90.toByte(), 0xab.toByte(),
            ),
            le,
        )
    }

    @Test
    fun parseServiceData128_extracts119ByteFrame() {
        val payload = ByteArray(frameSize) { it.toByte() }
        val uuidLe = Observer.uuidToLeBytes(serviceUuid)
        val ad = byteArrayOf(
            (1 + 16 + payload.size).toByte(),
            0x21.toByte(),
            *uuidLe,
            *payload,
        )
        val parsed = Observer.parseServiceData128(ad, serviceUuid)
        assertArrayEquals(payload, parsed)
    }

    @Test
    fun parseServiceData128_returnsNullForWrongUuid() {
        val ad = byteArrayOf(0x04, 0x21, 0x00, 0x00, 0x00)
        assertNull(Observer.parseServiceData128(ad, UUID.randomUUID()))
    }
}
