package net.guacamaya.aware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NanSyncTest {
    @Test fun antiEntropyReturnsOnlyLocallyMissingAtPeer() {
        val digest = NanDigestCodec.encode(listOf("a", "b"))
        assertEquals(listOf("c"), NanDigestCodec.missing(listOf("a", "b", "c"), digest))
        assertTrue(digest.size <= AwareConfig.SSI_MAX)
    }

    @Test fun dutyCycleAdaptsToBacklog() {
        assertEquals(NanDutyCycle.IDLE, NanDutyCycle.forBacklog(0))
        assertEquals(NanDutyCycle.ACTIVE, NanDutyCycle.forBacklog(1))
        assertEquals(NanDutyCycle.URGENT, NanDutyCycle.forBacklog(0, critical = true))
    }
}
