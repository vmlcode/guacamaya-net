package net.guacamaya.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE constants + parameters. See docs/payload-binary-layout.md.
 *
 * Frame layout inside the BLE service data, keyed by the 128-bit Guacamaya Service UUID:
 *
 *   byte 0        unsigned hop TTL (mutable, NOT signed)
 *   bytes 1..22   22 B signed payload
 *   bytes 23..54  32 B Ed25519 public key
 *   bytes 55..118 64 B Ed25519 signature
 *
 * The hop TTL lives OUTSIDE the signed payload on purpose: each relay decrements it,
 * and if it were inside the signed 22 B the origin's signature would break at the next
 * hop (node_id is bound to the origin's pubkey, so re-signing is not an option). The
 * signed payload also carries an informational origin TTL in its flags nibble, but the
 * authoritative live hop budget is this byte.
 *
 * Observer filters on the UUID in software (extended service-data is often omitted when
 * using hardware filters). The UUID is a placeholder — change before production. Same
 * value must be used by Observer when matching ScanRecord.serviceUuids.
 */
object BleConfig {

    /** 128-bit Service UUID. */
    val SERVICE_UUID: UUID = UUID.fromString("8d3d0001-2a1b-4c8e-9c0f-1234567890ab")

    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

    /** Byte offsets within the service-data blob. */
    const val TTL_OFFSET = 0
    const val PAYLOAD_OFFSET = 1
    const val PUBKEY_OFFSET = PAYLOAD_OFFSET + 22   // 23
    const val SIG_OFFSET = PUBKEY_OFFSET + 32       // 55

    /** Hop TTL an origin stamps on a fresh frame. Mirrors Flags origin hopTtl. */
    const val ORIGIN_HOP_TTL = 15

    /** Total service-data length: 1 B hop TTL + 22 B payload + 32 B pubkey + 64 B signature = 119 B. */
    const val SERVICE_DATA_SIZE = 1 + 22 + 32 + 64

    /**
     * AdvertisingSetParameters tuned for extended ADV (BLE 5).
     *
     * - Connectable false: we never pair.
     * - Primary PHY 1M (discovery) + secondary PHY CODED (data). The asymmetry
     *   flips the controller into extended-advertising mode (lifting the 31 B
     *   legacy cap) and CODED gives the broadest receiver compatibility —
     *   some controllers enable 1M+Coded by default and omit 2M. 1M/1M was
     *   rejected with status=4 on the broadcaster side; 1M/2M was broadcast
     *   clean but invisible to those receivers.
     * - Interval MIN (≈ 31 ms broadcast) — aggressive cadence so that older /
     *   buggy BLE controllers (e.g. Xiaomi Mi 10 Lite / Redmi Note 10 class)
     *   that drop extended-ADV secondary-PHY fragments still catch at least
     *   one complete frame per second. Costs more battery but SOS coverage is
     *   the priority over power budget in a rescue scenario.
     * - TX power ULTRA (max) for longest range.
     */
    val parameters: AdvertisingSetParameters = AdvertisingSetParameters.Builder()
        .setConnectable(false)
        .setScannable(false)
        .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
        .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
        .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
        .build()

    /**
     * Scan profiles for the Observer. Extended ADV (119 B service-data) requires
     * `legacy=false`; hardware ScanFilter is avoided (see Observer.kt).
     *
     * [AGGRESSIVE] — default: low-latency, immediate callbacks (`reportDelay=0`),
     * all PHYs. Best on Pixel / recent Qualcomm.
     *
     * [LEGACY_STACK] — for older MTK / Xiaomi-class stacks (Android 8–11) that
     * fail aggressive match or stop delivering callbacks: sticky match, fewer
     * concurrent matches, CODED PHY when supported (Broadcaster secondary PHY).
     */
    enum class ScanProfile { AGGRESSIVE, LEGACY_STACK }

    /** Build scan settings tuned for [profile] and this chip's PHY support. */
    fun scanSettings(adapter: BluetoothAdapter, profile: ScanProfile): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0) // no batching — faster "Received" on slow phones

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setLegacy(false)
            when (profile) {
                ScanProfile.AGGRESSIVE -> {
                    builder.setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    builder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    builder.setPhy(
                        if (adapter.isLeCodedPhySupported) {
                            ScanSettings.PHY_LE_ALL_SUPPORTED
                        } else {
                            BluetoothDevice.PHY_LE_1M
                        },
                    )
                }
                ScanProfile.LEGACY_STACK -> {
                    builder.setMatchMode(ScanSettings.MATCH_MODE_STICKY)
                    builder.setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    builder.setPhy(
                        when {
                            adapter.isLeCodedPhySupported -> ScanSettings.PHY_LE_ALL_SUPPORTED
                            else -> BluetoothDevice.PHY_LE_1M
                        },
                    )
                }
            }
        }

        return builder.build()
    }
}
