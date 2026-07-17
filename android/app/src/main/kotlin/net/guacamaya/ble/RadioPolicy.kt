package net.guacamaya.ble

import android.bluetooth.le.AdvertisingSetParameters

enum class RadioTier { CHARGING, NORMAL, LOW, CRITICAL }

data class RadioPolicy(
    val tier: RadioTier,
    val txPower: Int,
    val windowMs: Long,
    val cadenceMs: Long,
    val relayEnabled: Boolean,
) {
    val reducedRange: Boolean get() = tier == RadioTier.LOW || tier == RadioTier.CRITICAL
}

/** Pure hysteretic state machine; Android battery broadcasts are adapted by the service. */
object RadioPolicyEngine {
    fun next(previous: RadioTier, percent: Int, charging: Boolean): RadioPolicy {
        val tier = when {
            charging -> RadioTier.CHARGING
            previous == RadioTier.CRITICAL && percent < 15 -> RadioTier.CRITICAL
            percent <= 10 -> RadioTier.CRITICAL
            previous == RadioTier.LOW && percent < 25 -> RadioTier.LOW
            percent <= 20 -> RadioTier.LOW
            else -> RadioTier.NORMAL
        }
        return forTier(tier)
    }

    fun forTier(tier: RadioTier): RadioPolicy = when (tier) {
        RadioTier.CHARGING -> RadioPolicy(tier, AdvertisingSetParameters.TX_POWER_HIGH, 45_000L, 300_000L, true)
        RadioTier.NORMAL -> RadioPolicy(tier, AdvertisingSetParameters.TX_POWER_HIGH, 30_000L, 300_000L, true)
        RadioTier.LOW -> RadioPolicy(tier, AdvertisingSetParameters.TX_POWER_MEDIUM, 20_000L, 300_000L, true)
        RadioTier.CRITICAL -> RadioPolicy(tier, AdvertisingSetParameters.TX_POWER_ULTRA_LOW, 10_000L, 600_000L, false)
    }

    /** Stable ±3 s offset derived from the node id; equal-length windows still overlap. */
    fun jitterMs(nodeId: ByteArray): Long {
        var hash = 0
        nodeId.forEach { hash = hash * 31 + (it.toInt() and 0xff) }
        return ((hash.toLong() and 0x7fffffffL) % 6_001L) - 3_000L
    }
}
