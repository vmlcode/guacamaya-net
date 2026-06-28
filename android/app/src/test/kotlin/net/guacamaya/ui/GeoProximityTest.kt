package net.guacamaya.ui

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoProximityTest {

    @Test
    fun formatMeters_coLocated_showsJunto() {
        assertEquals("junto", GeoProximity.formatMeters(3f, coLocated = true))
    }

    @Test
    fun formatMeters_subMeter_showsCm() {
        assertEquals("80 cm", GeoProximity.formatMeters(0.8f))
    }

    @Test
    fun nearest_snapsToZeroWithinUncertainty() {
        GeoProximity.reset()
        val here = Location("test").apply {
            latitude = 4.6097
            longitude = -74.0817
            accuracy = 8f
        }
        val msg = net.guacamaya.mesh.MessageEntity(
            nodeId = byteArrayOf(1, 2, 3, 4),
            msgId = 1,
            tsUnix = 1_700_000_000L,
            latE7 = (4.609701 * 1e7).toInt(),
            lonE7 = (-74.081701 * 1e7).toInt(),
            sosType = 0,
            critical = false,
            hasHeavy = false,
            hopTtl = 5,
            batteryBucket = 2,
            pubkey = ByteArray(32),
            payloadRaw = ByteArray(22),
            rssi = -60,
            receivedAt = System.currentTimeMillis(),
        )
        val result = GeoProximity.nearest(here, listOf(msg))
        requireNotNull(result)
        assertTrue(result.coLocated)
        assertEquals(0f, result.distanceMeters, 0.01f)
    }

    @Test
    fun formatDistance_coLocated_includesRssiHint() {
        val r = GeoProximity.Result(
            nodeId = "aabb",
            distanceMeters = 0f,
            bearing = 0f,
            rssi = -50,
            critical = false,
            coLocated = true,
            uncertaintyMeters = 16f,
            rssiHint = GeoProximity.rssiProximityHint(-50),
        )
        assertTrue(GeoProximity.formatDistance(r).contains("BLE tocando"))
    }
}
