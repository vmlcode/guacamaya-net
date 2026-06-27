package org.sosnet.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PayloadTest {

    private val sampleNodeId = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte())

    private fun sample() = Payload(
        latE7 = 19_432_600,             // 19.4326° N
        lonE7 = -99_133_200,            // -99.1332° W
        tsUnix = 1_750_255_200L,        // 2025-06-13T18:00:00Z
        nodeId = sampleNodeId,
        flags = Flags.origin(hasHeavy = true, critical = true, batteryBucket = 3),
        sosType = SosType.MEDICAL,
        msgId = 0x2A7C,
    )

    @Test fun `round trip preserves all fields`() {
        val p = sample()
        val bytes = p.encode()
        assertEquals("encoded size", Payload.SIZE, bytes.size)

        val decoded = Payload.decode(bytes)
        assertEquals(p.latE7, decoded.latE7)
        assertEquals(p.lonE7, decoded.lonE7)
        assertEquals(p.tsUnix, decoded.tsUnix)
        assertArrayEquals(p.nodeId, decoded.nodeId)
        assertEquals(p.flags, decoded.flags)
        assertEquals(p.sosType, decoded.sosType)
        assertEquals(p.msgId, decoded.msgId)
    }

    @Test fun `crc catches single bit flip`() {
        val bytes = sample().encode()
        bytes[5] = (bytes[5].toInt() xor 0x01).toByte()   // flip one bit in lat
        assertThrows("CRC mismatch must throw", IllegalArgumentException::class.java) {
            Payload.decode(bytes)
        }
    }

    @Test fun `wrong size is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Payload.decode(ByteArray(21))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Payload.decode(ByteArray(23))
        }
    }

    @Test fun `flags round trip`() {
        for (heavy in listOf(true, false)) {
            for (crit in listOf(true, false)) {
                for (batt in 0..3) {
                    for (ttl in 0..15) {
                        val f = Flags(heavy, crit, batt, ttl)
                        val back = Flags.fromByte(f.toByte())
                        assertEquals("heavy=$heavy crit=$crit batt=$batt ttl=$ttl", f, back)
                    }
                }
            }
        }
    }

    @Test fun `hop ttl decrement saturates at zero`() {
        val f = Flags(hasHeavy = false, critical = false, batteryBucket = 1, hopTtl = 0)
        assertEquals(0, f.withDecrement().hopTtl)
        val f2 = Flags(hasHeavy = false, critical = false, batteryBucket = 1, hopTtl = 5)
        assertEquals(4, f2.withDecrement().hopTtl)
    }

    @Test fun `sos type unknown code falls back to OTHER`() {
        assertEquals(SosType.OTHER, SosType.fromCode(250))
    }

    @Test fun `encode is deterministic`() {
        val p = sample()
        assertArrayEquals(p.encode(), p.encode())
    }

    @Test fun `different msg_id produces different bytes`() {
        val a = sample().encode()
        val b = sample().copy(msgId = 0x2A7D).encode()
        assertNotEquals("msg_id byte must differ", a.toList(), b.toList())
    }

    @Test fun `origin flag has ttl 15`() {
        val f = Flags.origin(hasHeavy = true, critical = true, batteryBucket = 3)
        assertEquals(15, f.hopTtl)
    }
}

class Crc16Test {

    @Test fun `known vector`() {
        // CRC16-CCITT (0xFFFF) of "123456789" = 0x29B1.
        val crc = Crc16.ccitt("123456789".toByteArray())
        assertEquals(0x29B1, crc)
    }

    @Test fun `empty input yields init`() {
        assertEquals(0xFFFF, Crc16.ccitt(ByteArray(0)))
    }
}
