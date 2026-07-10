package net.guacamaya.ui

import net.guacamaya.mesh.RssiRange
import kotlin.math.floor

/**
 * Practical bearing aid for phones without AoA antennas. A rescuer slowly turns
 * with the phone at chest level; body shadow creates a broad RSSI maximum. It is
 * deliberately labelled low-confidence unless the scan covers several headings
 * and observes meaningful signal variation.
 */
class BodyBearingSweep {
    data class Result(
        val bearing: Float,
        val rssiSpanDb: Float,
        val headingBins: Int,
        val confident: Boolean,
    )

    private val samples = ArrayDeque<Sample>()
    private var lastRecordedAt: Long? = null

    fun record(heading: Float, rssi: Float, at: Long): Result? {
        val last = lastRecordedAt
        // Too soon → ignore. This also subsumes any backward clock jump (at < last ⇒
        // at - last < 0 < MIN_SAMPLE_INTERVAL_MS), so no separate monotonic guard.
        if (last != null && at - last < MIN_SAMPLE_INTERVAL_MS) return result()
        lastRecordedAt = at
        // Bounded RSSI: an out-of-band value (or a UI-side NaN/sentinel) must not skew
        // the max/min span or pick a bogus bearing. Clamp to the physical BLE band.
        samples.addLast(Sample(CompassMath.normalizeDegrees(heading), RssiRange.clampFloat(rssi), at))
        while (samples.isNotEmpty() && at - samples.first().at > WINDOW_MS) samples.removeFirst()
        return result()
    }

    fun result(): Result? {
        if (samples.size < MIN_SAMPLES) return null
        val max = samples.maxByOrNull { it.rssi } ?: return null
        val min = samples.minOf { it.rssi }
        val bins = samples.map { floor(it.heading / BIN_DEGREES).toInt() }.toSet().size
        val span = max.rssi - min
        return Result(
            bearing = max.heading,
            rssiSpanDb = span,
            headingBins = bins,
            confident = bins >= MIN_HEADING_BINS && span >= MIN_RSSI_SPAN_DB,
        )
    }

    private data class Sample(val heading: Float, val rssi: Float, val at: Long)

    companion object {
        /** Enough time for one slow 360-degree turn or two deliberate passes. */
        private const val WINDOW_MS = 45_000L
        private const val MIN_SAMPLE_INTERVAL_MS = 300L
        private const val MIN_SAMPLES = 8
        private const val BIN_DEGREES = 45f
        private const val MIN_HEADING_BINS = 4
        private const val MIN_RSSI_SPAN_DB = 6f
    }
}
