package net.guacamaya.ingest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnUploadPolicyTest {

    // Caracas-ish reference point in 1e7 fixed-point.
    private val lat = 104912345   // 10.4912345
    private val lon = -668798765  // -66.8798765

    @Test
    fun firstFrameOfSessionAlwaysUploads() {
        assertTrue(OwnUploadPolicy.shouldUpload(null, null, lat, lon))
    }

    @Test
    fun stationaryFixDoesNotReupload() {
        assertFalse(OwnUploadPolicy.shouldUpload(lat, lon, lat, lon))
    }

    @Test
    fun gpsJitterBelowThresholdSkips() {
        // +900 in latE7 ≈ 0.00009° ≈ 10 m north — below the 50 m gate.
        assertFalse(OwnUploadPolicy.shouldUpload(lat, lon, lat + 900, lon))
    }

    @Test
    fun realMovementUploads() {
        // +90_000 in lonE7 ≈ 0.009° ≈ ~1 km east — well above 50 m.
        assertTrue(OwnUploadPolicy.shouldUpload(lat, lon, lat, lon + 90_000))
    }

    @Test
    fun distanceIsRoughlyCorrect() {
        // ~1 km east; allow generous tolerance for latitude scaling.
        val d = OwnUploadPolicy.distanceMeters(lat, lon, lat, lon + 90_000)
        assertTrue("expected ~1 km, got $d", d in 800.0..1200.0)
    }
}
