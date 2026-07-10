package net.guacamaya.ui

import net.guacamaya.mesh.NeighborEstimateEntity
import net.guacamaya.mesh.NeighborState
import org.junit.Assert.assertEquals
import org.junit.Test

class SignalRadarTest {

    @Test
    fun closeRssiWithFarSignedGpsIsMarkedInconsistent() {
        val signal = target(distanceMeters = 25f, uncertaintyMeters = 15f)
        val gps = gps(distanceMeters = 4_000f, uncertaintyMeters = 20f)

        assertEquals(SignalRadar.GpsConsistency.INCONSISTENT, SignalRadar.gpsConsistency(gps, signal))
    }

    @Test
    fun overlappingGpsAndRssiBandsRemainUsable() {
        val signal = target(distanceMeters = 25f, uncertaintyMeters = 15f)
        val gps = gps(distanceMeters = 42f, uncertaintyMeters = 12f)

        assertEquals(SignalRadar.GpsConsistency.CONSISTENT, SignalRadar.gpsConsistency(gps, signal))
    }

    @Test
    fun staleOrPoorGpsWithWideUncertaintyDoesNotSuppressLegitimateNearSignal() {
        val signal = target(distanceMeters = 20f, uncertaintyMeters = 12f)
        val gps = gps(distanceMeters = 80f, uncertaintyMeters = 80f)

        assertEquals(SignalRadar.GpsConsistency.CONSISTENT, SignalRadar.gpsConsistency(gps, signal))
    }

    private fun target(distanceMeters: Float, uncertaintyMeters: Float) = SignalRadar.Target(
        nodeId = "aabbccdd",
        estimate = NeighborEstimateEntity(
            nodeId = "aabbccdd",
            updatedAt = 1L,
            lastSeenAt = 1L,
            filteredRssi = -62f,
            rssiVariance = 4f,
            distanceMeters = distanceMeters,
            uncertaintyMeters = uncertaintyMeters,
            source = 0,
            txPower = null,
            calibrated = false,
            stable = true,
            sampleCount = 8,
            state = NeighborState.HEARD.code,
        ),
        critical = true,
        state = NeighborState.HEARD,
    )

    private fun gps(distanceMeters: Float, uncertaintyMeters: Float) = GeoProximity.Result(
        nodeId = "aabbccdd",
        distanceMeters = distanceMeters,
        bearing = 0f,
        rssi = -70,
        critical = true,
        coLocated = false,
        uncertaintyMeters = uncertaintyMeters,
    )
}
