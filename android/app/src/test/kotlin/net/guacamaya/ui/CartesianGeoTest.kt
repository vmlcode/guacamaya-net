package net.guacamaya.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CartesianGeoTest {

    @Test
    fun enuFromBearingDistance_northIsPositiveY() {
        val (e, n) = CartesianGeo.enuFromBearingDistance(0f, 10f)
        assertEquals(0f, e, 0.01f)
        assertEquals(10f, n, 0.01f)
    }

    @Test
    fun enuFromBearingDistance_eastIsPositiveX() {
        val (e, n) = CartesianGeo.enuFromBearingDistance(90f, 10f)
        assertEquals(10f, e, 0.01f)
        assertEquals(0f, n, 0.01f)
    }

    @Test
    fun enuMeters_samePointIsZero() {
        val enu = CartesianGeo.enuMeters(4.6097, -74.0817, 4.6097, -74.0817)
        assertEquals(0f, enu.distanceM, 0.01f)
        assertTrue(abs(enu.eastM) < 0.01)
        assertTrue(abs(enu.northM) < 0.01)
    }

    @Test
    fun fitScaleMeters_includesAccuracyMargin() {
        assertEquals(4f, CartesianGeo.fitScaleMeters(1f, 1f, 0f), 0.01f)
        assertEquals(30f, CartesianGeo.fitScaleMeters(1f, 1f, 15f), 0.01f)
    }
}
