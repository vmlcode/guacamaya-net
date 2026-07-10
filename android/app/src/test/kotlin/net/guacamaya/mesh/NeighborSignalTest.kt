package net.guacamaya.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeighborSignalTest {

    @Test
    fun medianRejectsSingleMultipathSpikeBeforeEwma() {
        val filter = NeighborSignalFilter()
        repeat(6) { index ->
            filter.add(NeighborSignalSample(-70, null, ObservationSource.BLE_RSSI, observedAtMs = index * 200L))
        }
        val estimate = filter.add(NeighborSignalSample(-35, null, ObservationSource.BLE_RSSI, observedAtMs = 1_200L))

        assertTrue("one spike must not make the target look close", estimate.filteredRssi < -65f)
        assertEquals(7, filter.rawCount())
    }

    @Test
    fun uncalibratedBleDistanceCarriesBroadUncertainty() {
        val estimate = NeighborSignalFilter().add(
            NeighborSignalSample(-70, null, ObservationSource.BLE_RSSI, observedAtMs = 0L),
        )

        val distance = requireNotNull(estimate.estimatedDistanceMeters)
        val uncertainty = requireNotNull(estimate.uncertaintyMeters)
        assertTrue(!estimate.calibrated)
        assertTrue(uncertainty >= distance * 0.69f)
    }

    @Test
    fun pathLossProfilesAreCalibratablePerPhy() {
        val old = SignalDistance.profiles
        try {
            SignalDistance.installProfiles(PathLossProfiles(mapOf(2 to PathLossProfile(-50f, 2f, 1f))))
            val estimate = NeighborSignalFilter().add(NeighborSignalSample(-70, -50, ObservationSource.BLE_RSSI, 0L, primaryPhy = 2))
            assertEquals(10f, estimate.estimatedDistanceMeters!!, 0.01f)
            assertTrue("mutable TTL evidence stays untrusted", !estimate.calibrated)
        } finally { SignalDistance.installProfiles(old) }
    }

    @Test
    fun rttDistanceIsPreservedRatherThanRunningLogDistance() {
        val estimate = NeighborSignalFilter().add(
            NeighborSignalSample(
                rssi = -70,
                txPower = null,
                source = ObservationSource.NAN_RTT,
                observedAtMs = 0L,
                distanceMeters = 4.2f,
                distanceStdDevMeters = 0.8f,
            ),
        )

        assertEquals(4.2f, estimate.estimatedDistanceMeters!!, 0.001f)
        assertEquals(0.8f, estimate.uncertaintyMeters!!, 0.001f)
    }

    @Test
    fun livenessUsesHysteresis() {
        val now = 100_000L
        assertEquals(NeighborState.HEARD, NeighborState.at(now - 14_999L, now))
        assertEquals(NeighborState.DEGRADED, NeighborState.at(now - 15_000L, now))
        assertEquals(NeighborState.LOST, NeighborState.at(now - 50_000L, now))
    }

    @Test
    fun temporalWindowDropsStaleSamplesBeforeFilteringANewAdvertisingEpoch() {
        val filter = NeighborSignalFilter()
        repeat(3) { index ->
            filter.add(NeighborSignalSample(-88, null, ObservationSource.BLE_RSSI, observedAtMs = index * 200L))
        }

        val fresh = filter.add(NeighborSignalSample(-55, null, ObservationSource.BLE_RSSI, observedAtMs = 10_000L))

        assertEquals(-55f, fresh.filteredRssi, 0.01f)
        assertTrue(!fresh.stable)
    }

    @Test
    fun nonPhysicalRssiIsClampedToThePhysicalBand() {
        val high = NeighborSignalFilter().add(
            NeighborSignalSample(127, null, ObservationSource.BLE_RSSI, observedAtMs = 0L),
        )
        assertEquals(RssiRange.MAX_DBM.toFloat(), high.filteredRssi, 0.001f)

        val low = NeighborSignalFilter().add(
            NeighborSignalSample(-200, null, ObservationSource.BLE_RSSI, observedAtMs = 0L),
        )
        assertEquals(RssiRange.MIN_DBM.toFloat(), low.filteredRssi, 0.001f)
    }

    @Test
    fun slewCapStopsImpossibleProximityJump() {
        val filter = NeighborSignalFilter()
        filter.add(NeighborSignalSample(-90, null, ObservationSource.BLE_RSSI, observedAtMs = 0L))
        filter.add(NeighborSignalSample(-90, null, ObservationSource.BLE_RSSI, observedAtMs = 200L))
        filter.add(NeighborSignalSample(-35, null, ObservationSource.BLE_RSSI, observedAtMs = 231L))
        // Two "close" samples 31 ms apart flip the median to -35. Without the slew cap the
        // EWMA would climb to ~-74.6 dBm in one step; the cap holds it near the far baseline.
        val estimate = filter.add(NeighborSignalSample(-35, null, ObservationSource.BLE_RSSI, observedAtMs = 262L))

        assertTrue("filtered must not snap close on a physics-impossible jump", estimate.filteredRssi <= -88f)
    }

    @Test
    fun outOfOrderSampleIsRejectedAndPriorEstimateHeld() {
        val filter = NeighborSignalFilter()
        filter.add(NeighborSignalSample(-70, null, ObservationSource.BLE_RSSI, observedAtMs = 0L))
        filter.add(NeighborSignalSample(-70, null, ObservationSource.BLE_RSSI, observedAtMs = 200L))

        // A sample 100 ms in the PAST (beyond jitter tolerance) must be rejected: the ring
        // and the held estimate stay untouched, the regressed sample does not count.
        val held = filter.add(NeighborSignalSample(-40, null, ObservationSource.BLE_RSSI, observedAtMs = 100L))

        assertEquals(-70f, held.filteredRssi, 0.001f)
        assertEquals(2, filter.rawCount())
    }

    @Test
    fun outOfBandRttFallsThroughToTheLogDistanceModel() {
        val estimate = NeighborSignalFilter().add(
            NeighborSignalSample(
                rssi = -70,
                txPower = null,
                source = ObservationSource.NAN_RTT,
                observedAtMs = 0L,
                distanceMeters = 5_000f, // beyond RTT_MAX_METERS → bogus ranging result
            ),
        )

        val distance = requireNotNull(estimate.estimatedDistanceMeters)
        assertTrue("a 5 km RTT must not surface as a direct distance", distance < 1_000f)
        assertTrue(!estimate.calibrated)
    }
}
