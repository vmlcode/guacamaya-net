package net.guacamaya.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassMathTest {

    @Test
    fun relativeBearing_whenAligned_returnsZero() {
        assertEquals(0f, CompassMath.relativeBearing(90f, 90f), 0.01f)
    }

    @Test
    fun relativeBearing_wrapsAcrossNorth() {
        assertEquals(-10f, CompassMath.relativeBearing(350f, 0f), 0.01f)
        assertEquals(10f, CompassMath.relativeBearing(10f, 0f), 0.01f)
    }

    @Test
    fun smooth_movesTowardTarget() {
        val next = CompassMath.smooth(0f, 90f)
        assert(next in 5f..20f)
    }
}
