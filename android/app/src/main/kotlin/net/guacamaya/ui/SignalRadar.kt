package net.guacamaya.ui

import net.guacamaya.mesh.NeighborEstimateEntity
import net.guacamaya.mesh.NeighborState
import net.guacamaya.mesh.ObservationSource
import net.guacamaya.mesh.SignalDistance
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** UI-only selection and formatting for direct-neighbour radio evidence. */
object SignalRadar {
    enum class GpsConsistency { NO_SIGNED_GPS, CONSISTENT, INCONSISTENT }

    data class Target(
        val nodeId: String,
        val estimate: NeighborEstimateEntity,
        val critical: Boolean,
        val state: NeighborState,
    )

    fun targetFor(
        preferredNodeId: String?,
        estimates: List<NeighborEstimateEntity>,
        criticalByNode: Map<String, Boolean>,
        now: Long = System.currentTimeMillis(),
    ): Target? {
        val candidates = estimates.map {
            Target(
                nodeId = it.nodeId,
                estimate = it,
                critical = criticalByNode[it.nodeId] == true,
                state = NeighborState.at(it.lastSeenAt, now),
            )
        }.filter { it.state != NeighborState.LOST }
        // GPS and RF evidence must refer to the same origin. Do not decorate a
        // GPS target with the RSSI ring of an unrelated nearby relay/victim.
        if (preferredNodeId != null) return candidates.firstOrNull { it.nodeId == preferredNodeId }
        return candidates.maxByOrNull { it.estimate.filteredRssi }
    }

    fun rangeLabel(target: Target): String {
        if (!target.estimate.stable) return "señal detectada · estabilizando"
        val distance = target.estimate.distanceMeters ?: return "señal ${SignalDistance.temperature(target.estimate.filteredRssi)}"
        val uncertainty = target.estimate.uncertaintyMeters
        val base = "~${distance.roundToInt()} m"
        return if (uncertainty == null) base else "$base · ±${uncertainty.roundToInt()} m"
    }

    fun statusLabel(state: NeighborState): String = when (state) {
        NeighborState.HEARD -> "directa no verificada"
        NeighborState.DEGRADED -> "directa no verificada · intermitente"
        NeighborState.LOST -> "perdido"
    }

    /** TTL is mutable outside the signature, so it only suggests a direct RF hop.
     * Compare the two uncertain distance estimates at a deliberately conservative
     * 3 sigma boundary. An inconsistency stays visible to the rescuer, but never
     * displaces the GPS bearing or becomes protocol authority. */
    fun gpsConsistency(gps: GeoProximity.Result?, signal: Target?): GpsConsistency {
        if (gps == null || signal?.estimate?.stable != true) return GpsConsistency.NO_SIGNED_GPS
        val signalDistance = signal.estimate.distanceMeters ?: return GpsConsistency.NO_SIGNED_GPS
        val signalUncertainty = signal.estimate.uncertaintyMeters ?: signalDistance * 0.7f
        val combinedSigma = sqrt(
            gps.uncertaintyMeters * gps.uncertaintyMeters + signalUncertainty * signalUncertainty,
        )
        return if (abs(gps.distanceMeters - signalDistance) > CONSISTENCY_SIGMA * combinedSigma) {
            GpsConsistency.INCONSISTENT
        } else {
            GpsConsistency.CONSISTENT
        }
    }

    fun consistencyLabel(consistency: GpsConsistency): String = when (consistency) {
        GpsConsistency.NO_SIGNED_GPS -> "sin cruce GPS"
        GpsConsistency.CONSISTENT -> "coherente con GPS firmado"
        GpsConsistency.INCONSISTENT -> "RSSI inconsistente con GPS firmado"
    }

    fun sourceLabel(sourceCode: Int): String = when (ObservationSource.fromCode(sourceCode)) {
        ObservationSource.BLE_RSSI -> "BLE RSSI"
        ObservationSource.NAN_RSSI -> "NAN RSSI"
        ObservationSource.NAN_RTT -> "NAN RTT"
        ObservationSource.ANDROID_RANGING -> "Android Ranging"
    }

    private const val CONSISTENCY_SIGMA = 3f
}
