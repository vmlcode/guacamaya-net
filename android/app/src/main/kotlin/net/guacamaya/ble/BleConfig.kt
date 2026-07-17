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
 * Frame layout inside the BLE service data, keyed by the 128-bit GuacaMalla Service UUID:
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
 * using hardware filters). Same value must be used by Observer when matching
 * ScanRecord.serviceUuids.
 *
 * ## UUID transition (cutover + dual-scan)
 *
 * [SERVICE_UUID] is the production identity an origin **broadcasts** under. [LEGACY_UUIDS]
 * are older values the Observer still **accepts** during a rollout, so a mixed fleet does
 * not silently partition into two deaf meshes (matching is in software → free). To rotate
 * the UUID in the future: add the current value to [LEGACY_UUIDS], set a fresh
 * [SERVICE_UUID], ship; drop the legacy entry a release later once the fleet has updated.
 */
object BleConfig {

    /** Reserved service UUID for a future incompatible frame v2. Old v1 apps do
     * not scan it; new apps will dual-scan v1 + v2 during a disaster rollout. */
    val V2_SERVICE_UUID: UUID = UUID.fromString("bb34a92c-3b5f-4c13-9fa5-599a2ed26b3e")

    /**
     * 128-bit production Service UUID an origin broadcasts under. A random v4 (not a
     * registry-assigned value) — the 128-bit space makes collision negligible and no
     * central registration is needed. Replaced the hand-typed `8d3d0001-…` placeholder
     * (now in [LEGACY_UUIDS] for the transition window).
     */
    val SERVICE_UUID: UUID = UUID.fromString("a613421e-f059-4233-a4de-8b08de13fb7e")

    /**
     * Dedicated Service UUID for **control frames** (delivery / backend-resolve /
     * self-cancel). A separate UUID — NOT [SERVICE_UUID] and NOT [V2_SERVICE_UUID] — so
     * the Observer can tell a control PDU from an SOS PDU on the hot path by the UUID it
     * arrived under, with zero ambiguity and no payload-byte heuristics. A random v4
     * (collision-negligible, no registry). The 119 B on-wire envelope is identical to
     * the SOS frame; only the Service UUID and the 22 B payload content differ.
     *
     * Backend `/ingest` never sees this UUID — mules strip the leading TTL byte and
     * upload the bare 118 B frame, so the control UUID is purely an Android BLE
     * transport concern and has no mirror in `packages/shared`.
     */
    val CONTROL_SERVICE_UUID: UUID = UUID.fromString("a5f1f968-bf85-40b3-b84b-c0770d7d4afa")

    /** Versioned public group-chat transport. Variable-length frames; never parsed as SOS. */
    val CHAT_SERVICE_UUID: UUID = UUID.fromString("f3e1d6a2-7b49-4a93-93c1-64b4750cb8ad")

    /**
     * Retired UUIDs the Observer still matches so devices on an older build stay reachable
     * during a rollout. Broadcast is always [SERVICE_UUID]; only scanning accepts these.
     * Legacy UUIDs carry SOS frames only.
     */
    val LEGACY_UUIDS: List<UUID> = listOf(
        UUID.fromString("8d3d0001-2a1b-4c8e-9c0f-1234567890ab"),
    )

    /** SOS UUIDs the Observer accepts: current production + legacy rollout values. */
    val SOS_MATCH_UUIDS: List<UUID> = listOf(SERVICE_UUID) + LEGACY_UUIDS

    /** Control UUIDs the Observer accepts (single value, room to grow like SOS). */
    val CONTROL_MATCH_UUIDS: List<UUID> = listOf(CONTROL_SERVICE_UUID)

    /** Every UUID the Observer accepts, SOS first then control. */
    val MATCH_UUIDS: List<UUID> = SOS_MATCH_UUIDS + CONTROL_MATCH_UUIDS + CHAT_SERVICE_UUID

    val SERVICE_PARCEL_UUID: ParcelUuid = ParcelUuid(SERVICE_UUID)

    val CONTROL_PARCEL_UUID: ParcelUuid = ParcelUuid(CONTROL_SERVICE_UUID)
    val CHAT_PARCEL_UUID: ParcelUuid = ParcelUuid(CHAT_SERVICE_UUID)

    /** ParcelUuids for software matching of [MATCH_UUIDS]. */
    val MATCH_PARCEL_UUIDS: List<ParcelUuid> = MATCH_UUIDS.map { ParcelUuid(it) }

    /** What a service-data blob under a given UUID carries. */
    enum class FrameKind { SOS, CONTROL, CHAT }

    private val SOS_MATCH_SET: Set<ParcelUuid> = SOS_MATCH_UUIDS.map { ParcelUuid(it) }.toSet()

    /** Classify a parsed service-data UUID, or null if it is not a GuacaMalla UUID. */
    fun kindOf(parcel: ParcelUuid): FrameKind? = when {
        parcel == CONTROL_PARCEL_UUID -> FrameKind.CONTROL
        parcel == CHAT_PARCEL_UUID -> FrameKind.CHAT
        parcel in SOS_MATCH_SET -> FrameKind.SOS
        else -> null
    }

    /** Classify a raw-AD parse UUID, or null if it is not a GuacaMalla UUID. */
    fun kindOfUuid(uuid: UUID): FrameKind? = when {
        uuid == CONTROL_SERVICE_UUID -> FrameKind.CONTROL
        uuid == CHAT_SERVICE_UUID -> FrameKind.CHAT
        uuid in SOS_MATCH_UUIDS -> FrameKind.SOS
        else -> null
    }

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
     * 1M primary + 1M secondary extended ADV. Required for Redmi Note 10 (sweet)
     * class receivers — they often miss CODED secondary from Qualcomm broadcasters.
     * Prefer this as the default; fall back to [parameters] if the controller rejects it.
     */
    val parametersCompat: AdvertisingSetParameters = AdvertisingSetParameters.Builder()
        .setConnectable(false)
        .setScannable(false)
        .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
        .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
        .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
        .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MAX)
        .build()

    fun normalParameters(txPower: Int, active: Boolean = false): AdvertisingSetParameters =
        AdvertisingSetParameters.Builder()
            .setConnectable(false).setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M).setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .setInterval(if (active) AdvertisingSetParameters.INTERVAL_LOW else AdvertisingSetParameters.INTERVAL_MEDIUM)
            .setTxPowerLevel(txPower).build()

    /**
     * Scan profiles for the Observer. Extended ADV (119 B service-data) requires
     * `legacy=false`; hardware ScanFilter is avoided (see Observer.kt).
     *
     * [BALANCED] — normal mesh observation with all matches but without the
     * highest scan duty cycle. The active rescue radar promotes this to
     * [AGGRESSIVE].
     *
     * [AGGRESSIVE] — active rescue search: low-latency, immediate callbacks
     * (`reportDelay=0`), all PHYs. Best on Pixel / recent Qualcomm.
     *
     * [LEGACY_STACK] — for older MTK / Xiaomi-class stacks (Android 8–11) that
     * fail aggressive match or stop delivering callbacks: sticky match, fewer
     * concurrent matches, CODED PHY when supported (Broadcaster secondary PHY).
     */
    enum class ScanProfile { BALANCED, AGGRESSIVE, LEGACY_STACK }

    /** Build scan settings tuned for [profile] and this chip's PHY support. */
    fun scanSettings(adapter: BluetoothAdapter, profile: ScanProfile): ScanSettings {
        val builder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0) // no batching — faster "Received" on slow phones

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setLegacy(false)
            when (profile) {
                ScanProfile.BALANCED -> {
                    builder.setScanMode(ScanSettings.SCAN_MODE_BALANCED)
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
                    builder.setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    // MTK sweet (API 30): 1M-only matches Realme 1M/1M extended ADV best.
                    builder.setPhy(BluetoothDevice.PHY_LE_1M)
                }
            }
        }

        return builder.build()
    }
}
