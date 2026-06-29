package net.guacamaya.mesh

import net.guacamaya.proto.Flags
import net.guacamaya.proto.Payload
import net.guacamaya.proto.SosType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgePolicyTest {

    private val now = 1_800_000_000L

    @Test fun `fresh frames are accepted in both regimes`() {
        assertTrue(AgePolicy.accept(now, now, isHelpRequest = true))
        assertTrue(AgePolicy.accept(now, now, isHelpRequest = false))
        assertTrue(AgePolicy.accept(now - 60, now, isHelpRequest = false)) // 1 min old presence
    }

    @Test fun `presence stays fresh-only (old presence rejected)`() {
        assertTrue(AgePolicy.accept(now - 300, now, isHelpRequest = false))      // exactly at bound
        assertFalse(AgePolicy.accept(now - 301, now, isHelpRequest = false))     // just past
        assertFalse(AgePolicy.accept(now - 3_600, now, isHelpRequest = false))   // 1 h old presence
    }

    @Test fun `help requests are accepted across the store-and-forward window`() {
        assertTrue(AgePolicy.accept(now - 3_600, now, isHelpRequest = true))         // 1 h old SOS
        assertTrue(AgePolicy.accept(now - 23 * 3_600, now, isHelpRequest = true))    // 23 h old SOS
        assertTrue(AgePolicy.accept(now - 24 * 3_600, now, isHelpRequest = true))    // 24 h bound
        assertFalse(AgePolicy.accept(now - 24 * 3_600 - 1, now, isHelpRequest = true)) // past 24 h
    }

    @Test fun `far-future frames are rejected regardless of type`() {
        assertTrue(AgePolicy.accept(now + 300, now, isHelpRequest = true))   // within future skew
        assertFalse(AgePolicy.accept(now + 301, now, isHelpRequest = true))  // beyond future skew
        assertFalse(AgePolicy.accept(now + 301, now, isHelpRequest = false))
    }

    // ── Payload.isHelpRequest predicate ──────────────────────────────────────

    private fun payload(type: SosType, critical: Boolean) = Payload(
        latE7 = 0, lonE7 = 0, tsUnix = now, nodeId = ByteArray(4),
        flags = Flags(hasHeavy = false, critical = critical, batteryBucket = 0, hopTtl = 1),
        sosType = type, msgId = 1,
    )

    @Test fun `distress and typed requests are help requests but presence is not`() {
        assertTrue(payload(SosType.DISTRESS, critical = true).isHelpRequest)
        assertTrue(payload(SosType.MEDICAL, critical = false).isHelpRequest)   // typed, non-critical
        assertTrue(payload(SosType.WATER, critical = false).isHelpRequest)
        assertTrue(payload(SosType.OTHER, critical = true).isHelpRequest)      // critical OTHER
        assertFalse(payload(SosType.OTHER, critical = false).isHelpRequest)    // the presence heartbeat
    }
}
