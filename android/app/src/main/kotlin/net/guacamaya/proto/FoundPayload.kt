package net.guacamaya.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.guacamaya.crypto.Signer

/**
 * Found frame v1 ("Encontrado") — a finder marks an SOS victim as found, carried
 * to the backend as `foundFrames[]` in POST /ingest.
 *
 * Same envelope as the SOS frame: 22 B payload + 32 B pubkey + 64 B signature.
 * Byte-identical with `packages/shared/src/mesh/foundFrame.ts` on the backend.
 *
 *   off 0..3   latE7         int32 — finder latitude × 1e7
 *   off 4..7   lonE7         int32 — finder longitude × 1e7
 *   off 8..11  tsUnix        u32 unix seconds
 *   off 12..15 targetNodeId  SHA-256(victim pubkey)[0..3]
 *   off 16..17 targetMsgId   u16 — msgId of the SOS session being marked
 *   off 18..19 foundMsgId    u16 — CSPRNG per found report (dedupe entropy)
 *   off 20..21 crc           CRC16-CCITT over bytes 0..19
 *
 * Signature is domain-separated: Ed25519 over UTF8(FOUND_SIGN_DOMAIN) || payload22
 * — never over the bare payload, so SOS/control/found signatures are never
 * interchangeable.
 */
object FoundFrame {
    const val PAYLOAD_LEN = 22
    const val PUBKEY_LEN = 32
    const val SIG_LEN = 64
    const val FRAME_LEN = PAYLOAD_LEN + PUBKEY_LEN + SIG_LEN // 118 (upload, TTL stripped)
    const val ON_WIRE_LEN = FRAME_LEN + 1                    // 119 (leading hop TTL)

    const val PAYLOAD_OFF = 0
    const val PUBKEY_OFF = PAYLOAD_OFF + PAYLOAD_LEN         // 22
    const val SIG_OFF = PUBKEY_OFF + PUBKEY_LEN              // 54

    /** Mirrored verbatim in packages/shared/src/mesh/constants.ts. */
    const val FOUND_SIGN_DOMAIN = "guacamaya.found-frame.v1\n"

    /** The exact bytes the Ed25519 signature covers: UTF8 domain prefix + payload. */
    fun signBytes(payload22: ByteArray): ByteArray {
        val domain = FOUND_SIGN_DOMAIN.toByteArray(Charsets.UTF_8)
        val out = ByteArray(domain.size + payload22.size)
        System.arraycopy(domain, 0, out, 0, domain.size)
        System.arraycopy(payload22, 0, out, domain.size, payload22.size)
        return out
    }
}

/**
 * Encodable found payload. [encode] throws on invalid fields — mirrors
 * `encodeFoundPayload` on the backend. The caller appends pubkey + signature to
 * build the 118-byte frame (see [buildFoundFrame]).
 */
data class FoundPayload(
    val latE7: Int,
    val lonE7: Int,
    val tsUnix: Long,          // u32 unix seconds
    /** SHA-256(victim pubkey)[0..3], exactly 4 bytes. */
    val targetNodeId: ByteArray,
    val targetMsgId: Int,      // u16
    val foundMsgId: Int,       // u16
) {
    init {
        require(targetNodeId.size == 4) { "targetNodeId must be 4 bytes" }
        require(tsUnix in 0..0xFFFFFFFFL) { "tsUnix out of u32 range" }
        require(targetMsgId in 0..0xFFFF) { "targetMsgId out of u16 range" }
        require(foundMsgId in 0..0xFFFF) { "foundMsgId out of u16 range" }
    }

    /** Encode to a fresh 22-byte big-endian array with the trailing CRC16 filled in. */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(FoundFrame.PAYLOAD_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putInt(tsUnix.toInt())
        buf.put(targetNodeId)
        buf.putShort(targetMsgId.toShort())
        buf.putShort(foundMsgId.toShort())
        val withoutCrc = buf.array().copyOfRange(0, FoundFrame.PAYLOAD_LEN - 2)
        val crc = Crc16.ccitt(withoutCrc)
        buf.putShort(FoundFrame.PAYLOAD_LEN - 2, crc.toShort())
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FoundPayload) return false
        return latE7 == other.latE7 &&
            lonE7 == other.lonE7 &&
            tsUnix == other.tsUnix &&
            targetNodeId.contentEquals(other.targetNodeId) &&
            targetMsgId == other.targetMsgId &&
            foundMsgId == other.foundMsgId
    }

    override fun hashCode(): Int {
        var r = latE7
        r = 31 * r + lonE7
        r = 31 * r + tsUnix.hashCode()
        r = 31 * r + targetNodeId.contentHashCode()
        r = 31 * r + targetMsgId
        r = 31 * r + foundMsgId
        return r
    }

    companion object {
        /** Decode a 22-byte array; throws on size or CRC failure. Does not verify the signature. */
        fun decode(bytes: ByteArray): FoundPayload {
            require(bytes.size == FoundFrame.PAYLOAD_LEN) {
                "found payload must be ${FoundFrame.PAYLOAD_LEN} bytes, got ${bytes.size}"
            }
            val crcStored = ((bytes[FoundFrame.PAYLOAD_LEN - 2].toInt() and 0xFF) shl 8) or
                (bytes[FoundFrame.PAYLOAD_LEN - 1].toInt() and 0xFF)
            val crcComputed = Crc16.ccitt(bytes, 0, FoundFrame.PAYLOAD_LEN - 2) and 0xFFFF
            require(crcStored == crcComputed) { "crc mismatch" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val latE7 = buf.getInt(0)
            val lonE7 = buf.getInt(4)
            val tsUnix = buf.getInt(8).toLong() and 0xFFFFFFFFL
            val targetNodeId = bytes.copyOfRange(12, 16)
            val targetMsgId = buf.getShort(16).toInt() and 0xFFFF
            val foundMsgId = buf.getShort(18).toInt() and 0xFFFF
            return FoundPayload(latE7, lonE7, tsUnix, targetNodeId, targetMsgId, foundMsgId)
        }
    }
}

/**
 * Assemble the canonical 118-byte upload frame: payload22 + pubkey32 + sig64. The
 * signature covers [FoundFrame.signBytes] of the encoded payload (domain-prefixed).
 */
fun buildFoundFrame(payload: FoundPayload, privateKey: ByteArray): ByteArray {
    require(privateKey.size == FoundFrame.PUBKEY_LEN) {
        "private key must be ${FoundFrame.PUBKEY_LEN} bytes, got ${privateKey.size}"
    }
    val payloadBytes = payload.encode()
    val pubkey = Signer.derivePublicKey(privateKey)
    val sig = Signer.signMessage(privateKey, FoundFrame.signBytes(payloadBytes))
    val frame = ByteArray(FoundFrame.FRAME_LEN)
    System.arraycopy(payloadBytes, 0, frame, FoundFrame.PAYLOAD_OFF, FoundFrame.PAYLOAD_LEN)
    System.arraycopy(pubkey, 0, frame, FoundFrame.PUBKEY_OFF, FoundFrame.PUBKEY_LEN)
    System.arraycopy(sig, 0, frame, FoundFrame.SIG_OFF, FoundFrame.SIG_LEN)
    return frame
}
