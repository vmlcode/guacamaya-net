package org.sosnet.aware

/**
 * Constants for Wi-Fi Aware (NAN). Service name is the wire prefix subscribers
 * match on; SOSNet uses a stable prefix so any nearby node can subscribe to
 * the whole family via "sosnet::*" semantics (Android matches on exact prefix).
 *
 * SSI (Service Specific Info) carries up to 255 B at the MAC layer — no IP, no
 * auth, no user-facing pairing. See docs/protocol-flows.md Flow 4.
 */
object AwareConfig {

    /** NAN service name prefix. */
    const val SERVICE_NAME = "org.sosnet.v1"

    /** Service Specific Info payload — same 118 B layout as the BLE frame. */
    const val SSI_SIZE = 22 + 32 + 64

    /** Max bytes the NAN SSI field can carry per IEEE 802.11v. */
    const val SSI_MAX = 255
}
