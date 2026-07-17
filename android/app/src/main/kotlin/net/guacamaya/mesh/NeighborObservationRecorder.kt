package net.guacamaya.mesh

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.guacamaya.loc.PlatformLocation
import net.guacamaya.time.MeshClock
import kotlin.math.abs

/** Metadata attached by a transport after a frame has been authenticated. */
data class DirectRadioObservation(
    val nodeId: ByteArray,
    val rssi: Int,
    val source: ObservationSource,
    val txPower: Int? = null,
    val primaryPhy: Int = 0,
    val secondaryPhy: Int = 0,
    val distanceMeters: Float? = null,
    val distanceStdDevMeters: Float? = null,
    val proximityTrusted: Boolean = false,
)

fun interface NeighborObservationSink {
    suspend fun record(observation: DirectRadioObservation)
}

/**
 * Records direct radio evidence independently from [FloodRouter]. It is called
 * only after the signed origin frame passes validation and retains no relay data:
 * RSSI of a relay says where the relay is, not where the original victim is.
 */
class NeighborObservationRecorder(
    private val context: Context,
    private val dao: MessageDao,
    scope: CoroutineScope,
    private val now: () -> Long = { MeshClock.now(context) },
) : NeighborObservationSink {
    private val filters = HashMap<String, NeighborSignalFilter>()
    private val samplesSincePrune = HashMap<String, Int>()
    private val lastSeenByNode = HashMap<String, Long>()
    private val lastPersistAt = HashMap<String, Long>()
    private var cachedPosition: PositionSnapshot? = null
    private var lastPositionReadAt = 0L

    init {
        // State is derived at query time in the UI as well, but this persisted
        // refresh keeps a process restart from making every old entry look live.
        scope.launch {
            while (isActive) {
                delay(NEIGHBOR_STATE_REFRESH_MS)
                dao.refreshNeighborStates(
                    now = now(),
                    degradedAfter = NeighborState.DEGRADED_AFTER_MS,
                    lostAfter = NeighborState.LOST_AFTER_MS,
                )
            }
        }
    }

    override suspend fun record(observation: DirectRadioObservation) {
        // Admission gate: a wildly non-physical RSSI (noise-floor underflow, impossible
        // positive spike) is garbage from a malformed scan or an attacker. Drop it before
        // it refreshes liveness, feeds the filter, or hits the audit trail — the estimate
        // must never reflect it. The filter still clamps residual edge values defensively.
        if (!RssiRange.isPlausible(observation.rssi)) return
        val nodeId = observation.nodeId.toHex()
        val observedAt = now()
        lastSeenByNode[nodeId] = observedAt
        trimTrackedNodes()
        val estimate = filters.getOrPut(nodeId) { NeighborSignalFilter() }.add(
            NeighborSignalSample(
                rssi = observation.rssi,
                txPower = observation.txPower,
                source = observation.source,
                observedAtMs = observedAt,
                primaryPhy = observation.primaryPhy,
                secondaryPhy = observation.secondaryPhy,
                distanceMeters = observation.distanceMeters,
                distanceStdDevMeters = observation.distanceStdDevMeters,
                proximityTrusted = observation.proximityTrusted,
            ),
        )
        // The filter sees every PDU. Persist a representative sample at 5 Hz so
        // a low-latency scan does not turn SQLite/Compose into the bottleneck.
        // RTT measurements are sparse and are kept in full.
        val lastPersisted = lastPersistAt[nodeId]
        if (observation.source != ObservationSource.BLE_RSSI ||
            lastPersisted == null || observedAt - lastPersisted >= PERSIST_INTERVAL_MS
        ) {
            persist(nodeId, observedAt, observation, estimate)
            lastPersistAt[nodeId] = observedAt
        }
    }

    private suspend fun persist(
        nodeId: String,
        observedAt: Long,
        observation: DirectRadioObservation,
        estimate: NeighborSignalEstimate,
    ) {
        val position = positionAt(observedAt)
        dao.insertNeighborObservation(
            NeighborObservationEntity(
                nodeId = nodeId,
                observedAt = observedAt,
                rssi = observation.rssi,
                source = observation.source.code,
                txPower = observation.txPower,
                primaryPhy = observation.primaryPhy,
                secondaryPhy = observation.secondaryPhy,
                observerLatE7 = position?.latE7,
                observerLonE7 = position?.lonE7,
                observerAccuracyMeters = position?.accuracyMeters,
            ),
        )
        val state = NeighborState.at(observedAt, observedAt)
        dao.upsertNeighborEstimate(
            NeighborEstimateEntity(
                nodeId = nodeId,
                updatedAt = observedAt,
                lastSeenAt = observedAt,
                filteredRssi = estimate.filteredRssi,
                rssiVariance = estimate.rssiVariance,
                distanceMeters = estimate.estimatedDistanceMeters,
                uncertaintyMeters = estimate.uncertaintyMeters,
                source = estimate.source.code,
                txPower = estimate.txPower,
                calibrated = estimate.calibrated,
                stable = estimate.stable,
                sampleCount = estimate.sampleCount,
                state = state.code,
            ),
        )

        // Avoid a DELETE for every controller callback while keeping the persisted
        // ring very close to 50 entries even during a low-latency sweep.
        val sincePrune = (samplesSincePrune[nodeId] ?: 0) + 1
        if (sincePrune >= PRUNE_EVERY_SAMPLES) {
            dao.pruneNeighborObservations(nodeId)
            samplesSincePrune[nodeId] = 0
        } else {
            samplesSincePrune[nodeId] = sincePrune
        }
    }

    private fun trimTrackedNodes() {
        while (filters.size > MAX_TRACKED_NEIGHBORS) {
            val oldest = lastSeenByNode.minByOrNull { it.value }?.key ?: return
            filters.remove(oldest)
            samplesSincePrune.remove(oldest)
            lastSeenByNode.remove(oldest)
            lastPersistAt.remove(oldest)
        }
    }

    private fun positionAt(observedAt: Long): PositionSnapshot? {
        if (observedAt - lastPositionReadAt < POSITION_CACHE_MS) return cachedPosition
        lastPositionReadAt = observedAt
        val location = PlatformLocation.lastKnown(context) ?: return cachedPosition
        // A last-known fix older than two minutes is misleading to a future
        // multilateration solver, so retain no coordinates for this sample.
        if (location.time <= 0L || abs(observedAt - location.time) > MAX_POSITION_AGE_MS) {
            cachedPosition = null
            return null
        }
        cachedPosition = location.toSnapshot()
        return cachedPosition
    }

    private fun Location.toSnapshot(): PositionSnapshot = PositionSnapshot(
        latE7 = (latitude * 1e7).toInt().coerceIn(-900_000_000, 900_000_000),
        lonE7 = (longitude * 1e7).toInt().coerceIn(-1_800_000_000, 1_800_000_000),
        accuracyMeters = if (hasAccuracy()) accuracy else null,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class PositionSnapshot(
        val latE7: Int,
        val lonE7: Int,
        val accuracyMeters: Float?,
    )

    companion object {
        private const val PRUNE_EVERY_SAMPLES = 8
        private const val PERSIST_INTERVAL_MS = 200L
        private const val NEIGHBOR_STATE_REFRESH_MS = 5_000L
        private const val POSITION_CACHE_MS = 5_000L
        private const val MAX_POSITION_AGE_MS = 2 * 60_000L
        private const val MAX_TRACKED_NEIGHBORS = 500
    }
}
