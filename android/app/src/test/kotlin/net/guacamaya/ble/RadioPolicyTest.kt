package net.guacamaya.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RadioPolicyTest {
    @Test fun `low and critical thresholds use hysteresis`() {
        assertEquals(RadioTier.LOW, RadioPolicyEngine.next(RadioTier.NORMAL, 20, false).tier)
        assertEquals(RadioTier.LOW, RadioPolicyEngine.next(RadioTier.LOW, 24, false).tier)
        assertEquals(RadioTier.NORMAL, RadioPolicyEngine.next(RadioTier.LOW, 25, false).tier)
        assertEquals(RadioTier.CRITICAL, RadioPolicyEngine.next(RadioTier.LOW, 10, false).tier)
        assertEquals(RadioTier.CRITICAL, RadioPolicyEngine.next(RadioTier.CRITICAL, 14, false).tier)
        assertEquals(RadioTier.LOW, RadioPolicyEngine.next(RadioTier.CRITICAL, 15, false).tier)
    }

    @Test fun `charging overrides and critical disables relay`() {
        assertEquals(RadioTier.CHARGING, RadioPolicyEngine.next(RadioTier.CRITICAL, 2, true).tier)
        assertFalse(RadioPolicyEngine.forTier(RadioTier.CRITICAL).relayEnabled)
    }
}
