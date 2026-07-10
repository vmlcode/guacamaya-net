package net.guacamaya.mesh

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Radio observations are a search aid only. They never participate in frame
 * acceptance, relay policy, or receipt authorization: authenticated identity is
 * not proof of physical proximity.
 */
enum class ObservationSource(val code: Int) {
    BLE_RSSI(0),
    NAN_RSSI(1),
    NAN_RTT(2),
    ANDROID_RANGING(3),
    ;

    companion object {
        fun fromCode(code: Int): ObservationSource = entries.firstOrNull { it.code == code } ?: BLE_RSSI
    }
}

/** Direct-neighbour liveness. The values are intentionally conservative because
 * phone advertising cadence varies with relay rotation and vendor controllers. */
enum class NeighborState(val code: Int) {
    HEARD(0),
    DEGRADED(1),
    LOST(2),
    ;

    companion object {
        fun fromCode(code: Int): NeighborState = entries.firstOrNull { it.code == code } ?: HEARD

        fun at(lastSeenAt: Long, now: Long): NeighborState = when {
            now - lastSeenAt >= LOST_AFTER_MS -> LOST
            now - lastSeenAt >= DEGRADED_AFTER_MS -> DEGRADED
            else -> HEARD
        }

        /** A node can rotate its own frame around held relays, so use 5 s rather
         * than the controller's advertising interval as the expected cadence. */
        const val EXPECTED_DIRECT_FRAME_MS = 5_000L
        const val DEGRADED_AFTER_MS = 3 * EXPECTED_DIRECT_FRAME_MS
        const val LOST_AFTER_MS = 10 * EXPECTED_DIRECT_FRAME_MS
    }
}

data class NeighborSignalSample(
    val rssi: Int,
    val txPower: Int?,
    val source: ObservationSource,
    val observedAtMs: Long,
    val primaryPhy: Int = 0,
    val secondaryPhy: Int = 0,
    /** RTT / Android Ranging can provide a direct distance in a future phase. */
    val distanceMeters: Float? = null,
    val distanceStdDevMeters: Float? = null,
    /** BLE hop TTL is unsigned, so even an unchanged value is only a hint. */
    val proximityTrusted: Boolean = false,
)

data class PathLossProfile(
    val referencePowerDbm: Float = -59f,
    val pathLossExponent: Float,
    val residualSigmaDb: Float,
) {
    init {
        require(referencePowerDbm in -100f..20f)
        require(pathLossExponent in 1f..6f)
        require(residualSigmaDb in 0f..40f)
    }
}

/** Runtime-swappable field calibration, keyed by Android PHY. */
class PathLossProfiles(overrides: Map<Int, PathLossProfile> = emptyMap()) {
    private val profiles = DEFAULTS + overrides
    fun forPhy(primaryPhy: Int, secondaryPhy: Int): PathLossProfile =
        profiles[secondaryPhy.takeIf { it != 0 } ?: primaryPhy] ?: profiles.getValue(1)

    companion object {
        val DEFAULTS = mapOf(
            1 to PathLossProfile(-59f, 2.7f, 8.5f),
            2 to PathLossProfile(-58f, 2.9f, 9.0f),
            3 to PathLossProfile(-61f, 2.45f, 8.0f),
        )
    }
}

data class NeighborSignalEstimate(
    val filteredRssi: Float,
    val rssiVariance: Float,
    val estimatedDistanceMeters: Float?,
    val uncertaintyMeters: Float?,
    val txPower: Int?,
    val calibrated: Boolean,
    val source: ObservationSource,
    val primaryPhy: Int,
    val secondaryPhy: Int,
    /** True after at least three temporally recent samples contribute. */
    val stable: Boolean,
    val sampleCount: Int,
)

/**
 * Physical bounds for BLE RSSI. The Core Spec carries RSSI as an 8-bit signed int,
 * but consumer radios report ~-100..-30 dBm in practice; outside [-100, +20] a value
 * is noise-floor or a malformed / attacker-injected sample. The sonar is a search
 * aid only and never protocol authority, but a non-physical sample could still pin
 * the log-distance model to a bogus distance or snap proximity, so every admission
 * point clamps to this band. Shared by [NeighborSignalFilter],
 * [NeighborObservationRecorder], and [net.guacamaya.ui.BodyBearingSweep].
 */
object RssiRange {
    const val MIN_DBM = -100
    const val MAX_DBM = 20

    fun clamp(rssi: Int): Int = rssi.coerceIn(MIN_DBM, MAX_DBM)
    fun clampFloat(rssi: Float): Float = rssi.coerceIn(MIN_DBM.toFloat(), MAX_DBM.toFloat())
    fun isPlausible(rssi: Int): Boolean = rssi in MIN_DBM..MAX_DBM
}

/**
 * Tolerance for monotonic-clock admission. Controller/scan callbacks can report
 * timestamps a few tens of ms out of order without it meaning the clock regressed;
 * beyond that, an out-of-order PDU is rejected so the time window and slew rate stay
 * consistent.
 */
const val OBSERVATION_CLOCK_TOLERANCE_MS = 50L

/**
 * A small per-neighbour ring: median rejects body/multipath spikes, then EWMA is
 * applied in dBm (not metres) where the noise is closer to Gaussian. The raw ring
 * is deliberately kept in memory; Room stores a bounded audit trail separately.
 */
class NeighborSignalFilter(
    private val medianWindowMs: Long = DEFAULT_MEDIAN_WINDOW_MS,
    private val maxMedianSamples: Int = MAX_MEDIAN_SAMPLES,
    private val alpha: Float = DEFAULT_EWMA_ALPHA,
) {
    init {
        require(medianWindowMs > 0) { "medianWindowMs must be positive" }
        require(maxMedianSamples >= MIN_MEDIAN_SAMPLES) { "maxMedianSamples must be >= $MIN_MEDIAN_SAMPLES" }
        require(alpha in 0f..1f) { "alpha must be in 0..1" }
    }

    private val raw = ArrayDeque<NeighborSignalSample>(maxMedianSamples)
    private var filtered: Float? = null
    private var variance = 0f
    private var totalSamples = 0

    fun add(sample: NeighborSignalSample): NeighborSignalEstimate {
        // 1. Bounded RSSI: clamp a non-physical sample into the band so it cannot pin
        //    the log-distance model to a bogus distance or starve the ring. The
        //    recorder hard-rejects the worst of these upstream; this clamp covers
        //    direct/test use and residual edge values.
        val sanitized = if (RssiRange.isPlausible(sample.rssi)) sample
            else sample.copy(rssi = RssiRange.clamp(sample.rssi))

        val lastAt = raw.lastOrNull()?.observedAtMs
        if (lastAt != null) {
            // 2. Observation-time: a non-monotonic clock (out-of-order PDU, MeshClock
            //    regression) would corrupt both the time window below and the slew
            //    rate. Reject it — hold the prior estimate, leave the ring untouched.
            //    A small tolerance absorbs controller report jitter.
            if (sanitized.observedAtMs + OBSERVATION_CLOCK_TOLERANCE_MS < lastAt) {
                return estimateFor(sanitized)
            }
        }

        if (lastAt?.let { sanitized.observedAtMs - it > medianWindowMs } == true) {
            // A new advertising epoch after a long silence must not be averaged
            // against an old physical position or RF environment.
            raw.clear()
            filtered = null
            variance = 0f
        }
        raw.addLast(sanitized)
        while (raw.firstOrNull()?.let { sanitized.observedAtMs - it.observedAtMs > medianWindowMs } == true) {
            raw.removeFirst()
        }
        while (raw.size > maxMedianSamples) raw.removeFirst()
        val median = raw.map { it.rssi.toFloat() }.sorted()[raw.size / 2]
        val prior = filtered
        var next = if (prior == null) median else prior + alpha * (median - prior)
        if (prior != null) {
            // Exponentially weighted residual variance, stable with a short ring.
            val residual = median - prior
            variance = (1f - alpha) * (variance + alpha * residual * residual)
        }
        // 3. Reject impossible jumps: RSSI cannot slew arbitrarily fast. Capping the
        //    filtered value's rate of change stops a TX-power blip or an injected
        //    sample from snapping proximity from "far" to "close" faster than physics
        //    allows — the UI shows a real closure ramp, not an RF artifact.
        if (prior != null && lastAt != null) {
            val dtSec = ((sanitized.observedAtMs - lastAt).coerceAtLeast(0)) / 1000f
            if (dtSec > 0f) {
                val maxStep = MAX_SLEW_DB_PER_SEC * dtSec
                next = next.coerceIn(prior - maxStep, prior + maxStep)
            }
        }
        filtered = next
        totalSamples++
        return estimateFor(sanitized)
    }

    private fun estimateFor(sample: NeighborSignalSample): NeighborSignalEstimate =
        SignalDistance.estimate(
            filteredRssi = filtered ?: sample.rssi.toFloat(),
            rssiVariance = variance,
            sample = sample,
            stable = raw.size >= MIN_MEDIAN_SAMPLES,
            sampleCount = totalSamples,
        )

    fun rawCount(): Int = raw.size

    companion object {
        /** Time-bounded median avoids 7 slow advertisements becoming 20 s old. */
        const val DEFAULT_MEDIAN_WINDOW_MS = 8_000L
        const val MIN_MEDIAN_SAMPLES = 3
        /** Cap CPU work during 31 ms advertising while retaining a very fresh median. */
        const val MAX_MEDIAN_SAMPLES = 9
        const val DEFAULT_EWMA_ALPHA = 0.28f
        /**
         * Maximum filtered-RSSI rate of change (dB/s). Sustained body-carried RSSI rarely
         * exceeds ~25 dB/s; 40 dB/s still kills a physics-impossible snap (e.g. +50 dB in a
         * 31 ms advertising interval) while leaving room for genuine rapid closure. A backstop
         * on top of the median spike rejection, not a primary smoother.
         */
        const val MAX_SLEW_DB_PER_SEC = 40f
    }
}

/** BLE log-distance model with deliberately broad uncertainty. v1 does not carry
 * a transmitter's measured power, therefore its fallback must never be shown as a
 * precise location. v2 reserves a signed measured-power byte for this purpose. */
object SignalDistance {
    private const val DEFAULT_MEASURED_POWER_DBM = -59
    /** A direct RTT distance outside this band is below resolution or malformed. */
    private const val RTT_MIN_METERS = 0.5f
    private const val RTT_MAX_METERS = 1_000f

    @Volatile var profiles: PathLossProfiles = PathLossProfiles()

    fun installProfiles(value: PathLossProfiles) { profiles = value }

    fun estimate(
        filteredRssi: Float,
        rssiVariance: Float,
        sample: NeighborSignalSample,
        stable: Boolean,
        sampleCount: Int,
    ): NeighborSignalEstimate {
        // RTT / Android Ranging distance: trust it only inside a physically sane band.
        // A negative/zero/gargantuan value is below resolution or a malformed ranging
        // result — fall through to the log-distance model rather than surfacing a bogus
        // "direct distance" the UI would treat as ground truth.
        val rtt = sample.distanceMeters?.takeIf { it in RTT_MIN_METERS..RTT_MAX_METERS }
        if (rtt != null) {
            val stdDev = (sample.distanceStdDevMeters ?: rtt * 0.15f).coerceIn(0f, rtt)
            return NeighborSignalEstimate(
                filteredRssi = filteredRssi,
                rssiVariance = rssiVariance,
                estimatedDistanceMeters = rtt,
                uncertaintyMeters = stdDev,
                txPower = sample.txPower,
                calibrated = true,
                source = sample.source,
                primaryPhy = sample.primaryPhy,
                secondaryPhy = sample.secondaryPhy,
                stable = stable,
                sampleCount = sampleCount,
            )
        }

        val advertisedTx = sample.txPower?.takeIf { it in -100..20 }
        val model = profiles.forPhy(sample.primaryPhy, sample.secondaryPhy)
        val measuredPower = advertisedTx?.toFloat() ?: model.referencePowerDbm
        val distance = 10.0.pow(((measuredPower - filteredRssi) / (10f * model.pathLossExponent)).toDouble()).toFloat()
        val sigmaDb = sqrt(rssiVariance.coerceAtLeast(0f))
        val derivative = distance * (ln(10.0) / (10f * model.pathLossExponent)).toFloat()
        // Model error dominates the mathematics on consumer phones. Keep the band
        // honest even if a short sample window happens to be very stable.
        val calibratedSigma = maxOf(sigmaDb, model.residualSigmaDb)
        val uncertainty = maxOf(distance * if (advertisedTx != null) 0.45f else 0.70f, derivative * calibratedSigma)
        return NeighborSignalEstimate(
            filteredRssi = filteredRssi,
            rssiVariance = rssiVariance,
            estimatedDistanceMeters = distance,
            uncertaintyMeters = uncertainty,
            txPower = advertisedTx,
            calibrated = sample.proximityTrusted && advertisedTx != null,
            source = sample.source,
            primaryPhy = sample.primaryPhy,
            secondaryPhy = sample.secondaryPhy,
            stable = stable,
            sampleCount = sampleCount,
        )
    }

    fun temperature(filteredRssi: Float): String = when {
        filteredRssi >= -55f -> "muy cerca"
        filteredRssi >= -67f -> "cerca"
        filteredRssi >= -78f -> "media"
        else -> "débil"
    }

    /** Android BluetoothDevice PHY constants kept numeric to make this model JVM-only. */
}
