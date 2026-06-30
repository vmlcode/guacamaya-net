package net.guacamaya.aware

import net.guacamaya.ble.BleConfig

/**
 * Constants for Wi-Fi Aware (NAN). Service name is the wire prefix subscribers
 * match on; GuacaMalla uses a stable prefix so any nearby node can subscribe to
 * the whole family via "guacamalla::*" semantics (Android matches on exact prefix).
 *
 * SSI (Service Specific Info) carries up to 255 B at the MAC layer — no IP, no
 * auth, no user-facing pairing. GuacaMalla publishes the same 119 B frame used
 * by BLE service-data, including the mutable hop-TTL byte, so the normal
 * FloodRouter can verify/persist/relay frames from either radio plane.
 * See docs/protocol-flows.md Flow 4.
 */
object AwareConfig {

    /** NAN service name prefix. */
    const val SERVICE_NAME = "net.guacamaya.v1"

    /** Service Specific Info payload — same 119 B layout as BLE service-data. */
    const val SSI_SIZE = BleConfig.SERVICE_DATA_SIZE

    /** Max bytes the NAN SSI field can carry per IEEE 802.11v. */
    const val SSI_MAX = 255

    fun packFrame(ttl: Int, payload22: ByteArray, pub32: ByteArray, sig64: ByteArray): ByteArray {
        require(payload22.size == 22) { "payload must be 22 B" }
        require(pub32.size == 32) { "pubkey must be 32 B" }
        require(sig64.size == 64) { "signature must be 64 B" }
        return ByteArray(SSI_SIZE).also {
            it[BleConfig.TTL_OFFSET] = ttl.coerceIn(0, 255).toByte()
            payload22.copyInto(it, BleConfig.PAYLOAD_OFFSET)
            pub32.copyInto(it, BleConfig.PUBKEY_OFFSET)
            sig64.copyInto(it, BleConfig.SIG_OFFSET)
        }
    }
}
