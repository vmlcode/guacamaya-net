package net.guacamaya.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GuacaMalla application payload — 22 bytes, big-endian.
 *
 * See docs/payload-binary-layout.md for the byte map. The 2-byte CRC16 at the end
 * is computed over bytes 0..19 (cheap reject before the expensive Ed25519 verify).
 *
 * This object owns encode/decode. The 64-byte signature is appended separately
 * by the BLE layer (see ble/Broadcaster.kt); it is NOT part of the Payload itself.
 */
data class Payload(
    val latE7: Int,            // bytes 0..3   int32, lat × 1e7
    val lonE7: Int,            // bytes 4..7   int32, lon × 1e7
    val tsUnix: Long,          // bytes 8..11  uint32 (kept as Long for sign safety)
    val nodeId: ByteArray,     // bytes 12..15 4 raw bytes (first 4 of SHA-256(pubkey))
    val flags: Flags,          // byte  16
    val sosType: SosType,      // byte  17
    val msgId: Int,            // bytes 18..19 uint16 (kept as Int)
) {
    /**
     * True for an actual aid request (vs a presence/heartbeat beacon). Used to give
     * help requests a long store-and-forward age window while keeping presence frames
     * fresh-only (see mesh/AgePolicy). The presence heartbeat is the only sender that
     * is non-critical AND [SosType.OTHER]; every real request is critical or a specific
     * type. No spare flag bit exists, so this is derived from existing fields.
     */
    val isHelpRequest: Boolean get() = flags.critical || sosType != SosType.OTHER

    init {
        require(nodeId.size == 4) { "nodeId must be 4 bytes, got ${nodeId.size}" }
        require(tsUnix in 0..0xFFFFFFFFL) { "tsUnix out of uint32 range: $tsUnix" }
        require(msgId in 0..0xFFFF) { "msgId out of uint16 range: $msgId" }
        require(latE7 in -900_000_000..900_000_000) { "latE7 out of range: $latE7" }
        require(lonE7 in -1_800_000_000..1_800_000_000) { "lonE7 out of range: $lonE7" }
    }

    /** Encode to a fresh 22-byte big-endian array with the CRC16 filled in. */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putInt(tsUnix.toInt())
        buf.put(nodeId)
        buf.put(flags.toByte())
        buf.put(sosType.code.toByte())
        buf.putShort(msgId.toShort())
        val withoutCrc = ByteArray(SIZE - 2)
        buf.position(0)
        buf.get(withoutCrc)
        val crc = Crc16.ccitt(withoutCrc)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Payload) return false
        return latE7 == other.latE7 &&
            lonE7 == other.lonE7 &&
            tsUnix == other.tsUnix &&
            nodeId.contentEquals(other.nodeId) &&
            flags == other.flags &&
            sosType == other.sosType &&
            msgId == other.msgId
    }

    override fun hashCode(): Int {
        var r = latE7
        r = 31 * r + lonE7
        r = 31 * r + tsUnix.hashCode()
        r = 31 * r + nodeId.contentHashCode()
        r = 31 * r + flags.hashCode()
        r = 31 * r + sosType.hashCode()
        r = 31 * r + msgId
        return r
    }

    companion object {
        const val SIZE = 22

        /** Decode a 22-byte array. Throws on malformed input — caller should catch. */
        fun decode(bytes: ByteArray): Payload {
            require(bytes.size == SIZE) { "payload must be $SIZE bytes, got ${bytes.size}" }
            val crcStored = ((bytes[SIZE - 2].toInt() and 0xFF) shl 8) or (bytes[SIZE - 1].toInt() and 0xFF)
            val crcComputed = Crc16.ccitt(bytes, 0, SIZE - 2).toInt() and 0xFFFF
            require(crcStored == crcComputed) {
                "CRC mismatch: stored=$crcStored computed=$crcComputed"
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val latE7 = buf.int
            val lonE7 = buf.int
            val tsUnix = buf.int.toLong() and 0xFFFFFFFFu.toLong()
            val nodeId = ByteArray(4).also { buf.get(it) }
            val flags = Flags.fromByte(buf.get())
            val sosType = SosType.fromCode(buf.get().toInt() and 0xFF)
            val msgId = buf.short.toInt() and 0xFFFF
            return Payload(latE7, lonE7, tsUnix, nodeId, flags, sosType, msgId)
        }
    }
}

/**
 * Bitfield at byte 16 of the payload.
 *
 * bit 0     has_heavy    (1 = Wi-Fi Aware heavy payload available)
 * bit 1     critical     (1 = immediate life threat)
 * bits 2..3 battery      (0=empty … 3=full)
 * bits 4..7 hop_ttl      (0..15; 15 at origin, decremented at each hop)
 */
data class Flags(
    val hasHeavy: Boolean,
    val critical: Boolean,
    val batteryBucket: Int,
    val hopTtl: Int,
) {
    init {
        require(batteryBucket in 0..3) { "batteryBucket must be 0..3, got $batteryBucket" }
        require(hopTtl in 0..15) { "hopTtl must be 0..15, got $hopTtl" }
    }

    fun toByte(): Byte {
        val heavy = if (hasHeavy) 1 else 0
        val crit = if (critical) 2 else 0
        val batt = (batteryBucket and 0x03) shl 2
        val ttl = (hopTtl and 0x0F) shl 4
        return (heavy or crit or batt or ttl).toByte()
    }

    fun withDecrement(): Flags = if (hopTtl == 0) this else copy(hopTtl = hopTtl - 1)

    companion object {
        fun fromByte(b: Byte): Flags = fromInt(b.toInt() and 0xFF)

        fun fromInt(v: Int): Flags {
            val heavy = (v and 0x01) != 0
            val crit = (v and 0x02) != 0
            val batt = (v shr 2) and 0x03
            val ttl = (v shr 4) and 0x0F
            return Flags(heavy, crit, batt, ttl)
        }

        fun origin(hasHeavy: Boolean, critical: Boolean, batteryBucket: Int): Flags =
            Flags(hasHeavy, critical, batteryBucket, hopTtl = 15)
    }
}

enum class SosType(val code: Int, val label: String) {
    MEDICAL(0, "medical"),
    DISTRESS(1, "distress"),
    FOOD(2, "food"),
    WATER(3, "water"),
    SHELTER(4, "shelter"),
    FIRE(5, "fire"),
    VIOLENCE(6, "violence"),
    OTHER(7, "other");

    companion object {
        fun fromCode(code: Int): SosType =
            values().firstOrNull { it.code == code } ?: OTHER
    }
}
