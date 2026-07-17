package net.guacamaya.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyBearingSweepTest {

    @Test
    fun onlyReportsBearingAfterHeadingCoverageAndSignalContrast() {
        val sweep = BodyBearingSweep()
        listOf(
            0f to -75f,
            45f to -72f,
            90f to -58f,
            135f to -70f,
            180f to -76f,
            225f to -74f,
            270f to -73f,
            315f to -75f,
        ).forEachIndexed { index, (heading, rssi) ->
            sweep.record(heading, rssi, index * 500L)
        }

        val result = requireNotNull(sweep.result())
        assertTrue(result.confident)
        assertEquals(90f, result.bearing, 0.01f)
    }

    @Test
    fun stableSignalDoesNotPretendToHaveABearing() {
        val sweep = BodyBearingSweep()
        listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEachIndexed { index, heading ->
            sweep.record(heading, -70f, index * 500L)
        }

        assertFalse(requireNotNull(sweep.result()).confident)
    }

    @Test
    fun outOfBandRssiIsClampedBeforeItEntersTheWindow() {
        val sweep = BodyBearingSweep()
        // A +Inf sentinel (the shape a malformed scan / NaN arithmetic can surface) must
        // be clamped, not stored raw: otherwise max = +Inf and the span/bearing go non-finite.
        listOf(
            0f to -75f,
            45f to Float.POSITIVE_INFINITY,
            90f to -76f,
            135f to -74f,
            180f to -73f,
            225f to -75f,
            270f to -72f,
            315f to -74f,
        ).forEachIndexed { index, (heading, rssi) ->
            sweep.record(heading, rssi, index * 500L)
        }

        val result = requireNotNull(sweep.result())
        assertTrue("clamped span must stay finite", result.rssiSpanDb.isFinite())
        assertTrue("clamped span must stay within the physical band", result.rssiSpanDb <= 120f)
    }
}
