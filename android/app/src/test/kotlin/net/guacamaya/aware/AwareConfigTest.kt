package net.guacamaya.aware

import net.guacamaya.ble.BleConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AwareConfigTest {

    @Test
    fun packFrame_matchesBleServiceDataLayout() {
        val payload = ByteArray(22) { (it + 1).toByte() }
        val pub = ByteArray(32) { (it + 40).toByte() }
        val sig = ByteArray(64) { (it + 90).toByte() }

        val frame = AwareConfig.packFrame(ttl = 7, payload22 = payload, pub32 = pub, sig64 = sig)

        assertEquals(BleConfig.SERVICE_DATA_SIZE, AwareConfig.SSI_SIZE)
        assertEquals(119, frame.size)
        assertEquals(7, frame[BleConfig.TTL_OFFSET].toInt())
        assertArrayEquals(payload, frame.copyOfRange(BleConfig.PAYLOAD_OFFSET, BleConfig.PUBKEY_OFFSET))
        assertArrayEquals(pub, frame.copyOfRange(BleConfig.PUBKEY_OFFSET, BleConfig.SIG_OFFSET))
        assertArrayEquals(sig, frame.copyOfRange(BleConfig.SIG_OFFSET, AwareConfig.SSI_SIZE))
    }
}
