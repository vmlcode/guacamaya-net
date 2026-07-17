package net.guacamaya.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import net.guacamaya.crypto.Signer

/**
 * Control frame v1 — delivery / backend-resolve / self-cancel carried over BLE.
 *
 * Same envelope as the SOS frame: 22 B payload + 32 B pubkey + 64 B signature, with
 * a leading mutable hop-TTL byte on the wire (stripped here). Byte-identical with
 * `packages/shared/src/mesh/controlFrame.ts` and `constants.ts` on the backend —
 * the golden vectors in [ControlPayloadTest] lock the two sides together.
 *
 *   off 0      type            0x01 delivery | 0x02 backend-resolve | 0x03 self-cancel
 *   off 1..4   targetNodeId    SHA-256(victim pubkey)[0..3]
 *   off 5..6   targetMsgId     u16 — msgId of the SOS session being addressed
 *   off 7..10  issuedAt        u32 unix seconds
 *   off 11..14 expiresAt       u32 unix seconds
 *   off 15..16 ctrlMsgId       u16 — CSPRNG per control message (dedupe key)
 *   off 17..19 reserved        must be zero (reject otherwise; no silent v2)
 *   off 20..21 crc             CRC16-CCITT over bytes 0..19
 *
 * Signature is domain-separated: Ed25519 over UTF8([CONTROL_SIGN_DOMAIN]) || payload22
 * — never over the bare payload, so SOS and control signatures are not interchangeable.
 */
object ControlFrame {
    const val PAYLOAD_LEN = 22
    const val PUBKEY_LEN = 32
    const val SIG_LEN = 64
    const val FRAME_LEN = PAYLOAD_LEN + PUBKEY_LEN + SIG_LEN // 118 (upload, TTL stripped)
    const val ON_WIRE_LEN = FRAME_LEN + 1                   // 119 (leading hop TTL)

    const val PAYLOAD_OFF = 0
    const val PUBKEY_OFF = PAYLOAD_OFF + PAYLOAD_LEN        // 22
    const val SIG_OFF = PUBKEY_OFF + PUBKEY_LEN             // 54

    /**
     * Domain separation for the control-frame Ed25519 signature:
     * sig = Ed25519(sk, UTF8(CONTROL_SIGN_DOMAIN) || payload22). An SOS payload
     * signature can never be replayed as a control frame or vice versa.
     * Mirrored verbatim in packages/shared/src/mesh/constants.ts.
     */
    const val CONTROL_SIGN_DOMAIN = "guacamaya.control-frame.v1\n"

    /** The exact bytes the Ed25519 signature covers: UTF8 domain prefix + payload. */
    fun signBytes(payload22: ByteArray): ByteArray {
        val domain = CONTROL_SIGN_DOMAIN.toByteArray(Charsets.UTF_8)
        val out = ByteArray(domain.size + payload22.size)
        System.arraycopy(domain, 0, out, 0, domain.size)
        System.arraycopy(payload22, 0, out, domain.size, payload22.size)
        return out
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** Lowercase hex — matches noble's `bytesToHex` used on the backend. */
    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    /** Parse lowercase/uppercase hex into bytes. Throws on odd length / bad char. */
    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex must have even length, got ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = parseHexNibble(hex[i * 2])
            val lo = parseHexNibble(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun parseHexNibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("invalid hex char: $c")
    }

    private fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    /** First 4 bytes of SHA-256(pubkey) — the mesh node id. */
    fun nodeIdOf(pubkey: ByteArray): ByteArray = sha256(pubkey).copyOfRange(0, 4)

    /**
     * Cheap SOS-vs-control discriminator run *before* the expensive signature check
     * (the FloodRouter hot path). True only if [payload22] could plausibly be a
     * control payload: a control type byte, the reserved bytes 17..19 zero, and a
     * valid control CRC over 0..19.
     *
     * This is deliberately NOT conclusive. An SOS payload's high byte is the high
     * byte of latE7, which is 0x01..0x03 for plausible latitudes (~16°/33°/50°),
     * and both frames compute CRC16-CCITT over 0..19 — so type + CRC alone do not
     * separate them. The reserved-zero bytes are the structural tell, but a
     * contrived SOS frame could still satisfy all four. That is harmless: a false
     * positive simply routes into [verifyControlFrame], whose domain-separated
     * signature check fails, and the caller falls back through to the SOS cascade.
     * A genuine control payload always passes this.
     */
    fun isCandidate(payload22: ByteArray): Boolean {
        if (payload22.size != PAYLOAD_LEN) return false
        val type = payload22[0].toInt() and 0xFF
        if (type != 0x01 && type != 0x02 && type != 0x03) return false
        if (payload22[17] != 0.toByte() || payload22[18] != 0.toByte() || payload22[19] != 0.toByte()) return false
        val crcStored = ((payload22[20].toInt() and 0xFF) shl 8) or (payload22[21].toInt() and 0xFF)
        return crcStored == (Crc16.ccitt(payload22, 0, 20) and 0xFFFF)
    }
}

/** Control payload type codes — mirrors `CONTROL_TYPE_*` in constants.ts. */
enum class ControlType(val code: Int, val maxLifetimeSec: Long) {
    DELIVERY(0x01, 30L * 24 * 3600),
    BACKEND_RESOLVE(0x02, 30L * 24 * 3600),
    SELF_CANCEL(0x03, 24L * 3600);

    companion object {
        fun fromCode(code: Int): ControlType? = values().firstOrNull { it.code == code }
    }
}

/**
 * Encodable control payload. [encode] throws on invalid fields — mirrors
 * `encodeControlPayload` on the backend. The caller appends pubkey + signature to
 * build the 118-byte frame (see [buildControlFrame]).
 */
data class ControlPayload(
    val type: ControlType,
    /** SHA-256(victim pubkey)[0..3], exactly 4 bytes. */
    val targetNodeId: ByteArray,
    val targetMsgId: Int,      // u16
    val issuedAt: Long,        // u32 unix seconds
    val expiresAt: Long,       // u32 unix seconds
    val ctrlMsgId: Int,        // u16
) {
    init {
        require(targetNodeId.size == 4) { "targetNodeId must be 4 bytes" }
        require(targetMsgId in 0..0xFFFF) { "targetMsgId out of u16 range" }
        require(ctrlMsgId in 0..0xFFFF) { "ctrlMsgId out of u16 range" }
        require(issuedAt in 0..0xFFFFFFFFL) { "issuedAt out of u32 range" }
        require(expiresAt in 0..0xFFFFFFFFL) { "expiresAt out of u32 range" }
        require(expiresAt > issuedAt) { "expiresAt must be after issuedAt" }
        require(expiresAt - issuedAt <= type.maxLifetimeSec) { "lifetime exceeds per-type maximum" }
    }

    /** Encode to a fresh 22-byte big-endian array with the trailing CRC16 filled in. */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(ControlFrame.PAYLOAD_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.put(type.code.toByte())
        buf.put(targetNodeId)
        buf.putShort(targetMsgId.toShort())
        buf.putInt(issuedAt.toInt())
        buf.putInt(expiresAt.toInt())
        buf.putShort(ctrlMsgId.toShort())
        // bytes 17..19 stay zero (reserved)
        buf.putShort(0)
        val withoutCrc = buf.array().copyOfRange(0, ControlFrame.PAYLOAD_LEN - 2)
        val crc = Crc16.ccitt(withoutCrc)
        buf.putShort(ControlFrame.PAYLOAD_LEN - 2, crc.toShort())
        return buf.array()
    }

    companion object {
        /** Decode a 22-byte array; throws on size or CRC failure. Does not verify the signature. */
        fun decode(bytes: ByteArray): ControlPayload {
            require(bytes.size == ControlFrame.PAYLOAD_LEN) {
                "control payload must be ${ControlFrame.PAYLOAD_LEN} bytes, got ${bytes.size}"
            }
            val crcStored = ((bytes[ControlFrame.PAYLOAD_LEN - 2].toInt() and 0xFF) shl 8) or
                (bytes[ControlFrame.PAYLOAD_LEN - 1].toInt() and 0xFF)
            val crcComputed = Crc16.ccitt(bytes, 0, ControlFrame.PAYLOAD_LEN - 2) and 0xFFFF
            require(crcStored == crcComputed) { "crc mismatch" }
            val type = ControlType.fromCode(bytes[0].toInt() and 0xFF)
                ?: throw IllegalArgumentException("unknown control type 0x${(bytes[0].toInt() and 0xFF).toString(16)}")
            require(bytes[17] == 0.toByte() && bytes[18] == 0.toByte() && bytes[19] == 0.toByte()) {
                "reserved bytes not zero"
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val targetNodeId = ByteArray(4).also { buf.position(1); buf.get(it) }
            buf.position(5)
            val targetMsgId = buf.short.toInt() and 0xFFFF
            val issuedAt = (buf.int.toLong() and 0xFFFFFFFFL)
            val expiresAt = (buf.int.toLong() and 0xFFFFFFFFL)
            val ctrlMsgId = buf.short.toInt() and 0xFFFF
            return ControlPayload(type, targetNodeId, targetMsgId, issuedAt, expiresAt, ctrlMsgId)
        }
    }
}

/**
 * A control frame that passed the full verification cascade. The signature and the
 * per-frame pubkey are retained so the caller can dedupe by signature and pin the
 * issuer for trust state.
 */
data class VerifiedControlFrame(
    val type: ControlType,
    val targetNodeId: ByteArray,
    val targetNodeIdHex: String,
    val targetMsgId: Int,
    val issuedAt: Long,
    val expiresAt: Long,
    val ctrlMsgId: Int,
    val issuerPublicKey: ByteArray,
    val issuerPublicKeyHex: String,
    /** SHA-256(issuer pubkey)[0..3] hex — dedupe namespace with ctrlMsgId. */
    val issuerNodeIdHex: String,
    val signature: ByteArray,
    val signatureHex: String,
    val payload: ByteArray,
    /** Canonical 118-byte upload frame (TTL stripped). */
    val frame: ByteArray,
)

sealed interface ControlFrameResult {
    data class Ok(val control: VerifiedControlFrame) : ControlFrameResult
    data class Err(val reason: String) : ControlFrameResult
}

/**
 * Assemble the canonical 118-byte upload frame: payload22 + pubkey32 + sig64. The
 * signature covers [ControlFrame.signBytes] of the encoded payload (domain-prefixed).
 * Used by the Android self-cancel path and by tests; the backend owns delivery /
 * backend-resolve minting.
 */
fun buildControlFrame(payload: ControlPayload, privateKey: ByteArray): ByteArray {
    require(privateKey.size == ControlFrame.PUBKEY_LEN) {
        "private key must be ${ControlFrame.PUBKEY_LEN} bytes, got ${privateKey.size}"
    }
    val payloadBytes = payload.encode()
    val pubkey = Signer.derivePublicKey(privateKey)
    val sig = Signer.signMessage(privateKey, ControlFrame.signBytes(payloadBytes))
    val frame = ByteArray(ControlFrame.FRAME_LEN)
    System.arraycopy(payloadBytes, 0, frame, ControlFrame.PAYLOAD_OFF, ControlFrame.PAYLOAD_LEN)
    System.arraycopy(pubkey, 0, frame, ControlFrame.PUBKEY_OFF, ControlFrame.PUBKEY_LEN)
    System.arraycopy(sig, 0, frame, ControlFrame.SIG_OFF, ControlFrame.SIG_LEN)
    return frame
}

/**
 * Full zero-trust verification cascade, cheapest first — mirrors
 * `verifyControlFrame` on the backend and the FloodRouter control branch:
 *   size → type/reserved → CRC → time window → Ed25519 (domain-prefixed) →
 *   authorization (backend pin for 0x01/0x02, self-binding for 0x03).
 * Dedupe (issuerNodeId+ctrlMsgId) and replay-by-signature stay with the caller,
 * which owns the persistent stores.
 *
 * @param bytes 118-byte upload frame, or a 119-byte on-wire frame (leading TTL byte
 *   stripped when present).
 * @param nowMs wall clock (System.currentTimeMillis()).
 * @param backendPublicKeyHex pinned backend Ed25519 public key (lowercase hex).
 *   Required to accept delivery / backend-resolve frames; null rejects them.
 */
fun verifyControlFrame(
    bytes: ByteArray,
    nowMs: Long,
    backendPublicKeyHex: String?,
): ControlFrameResult {
    val frame = if (bytes.size == ControlFrame.ON_WIRE_LEN) bytes.copyOfRange(1, bytes.size) else bytes
    if (frame.size != ControlFrame.FRAME_LEN) {
        return ControlFrameResult.Err(
            "bad control frame length ${bytes.size} (need ${ControlFrame.FRAME_LEN})"
        )
    }

    val payload = frame.copyOfRange(ControlFrame.PAYLOAD_OFF, ControlFrame.PAYLOAD_OFF + ControlFrame.PAYLOAD_LEN)
    val pubkey = frame.copyOfRange(ControlFrame.PUBKEY_OFF, ControlFrame.PUBKEY_OFF + ControlFrame.PUBKEY_LEN)
    val sig = frame.copyOfRange(ControlFrame.SIG_OFF, ControlFrame.SIG_OFF + ControlFrame.SIG_LEN)

    val typeCode = payload[0].toInt() and 0xFF
    val type = ControlType.fromCode(typeCode)
    if (type == null) return ControlFrameResult.Err("unknown control type 0x${typeCode.toString(16)}")
    if (payload[17] != 0.toByte() || payload[18] != 0.toByte() || payload[19] != 0.toByte()) {
        return ControlFrameResult.Err("reserved bytes not zero")
    }

    val crcStored = ((payload[20].toInt() and 0xFF) shl 8) or (payload[21].toInt() and 0xFF)
    if (crcStored != (Crc16.ccitt(payload, 0, 20) and 0xFFFF)) {
        return ControlFrameResult.Err("crc mismatch")
    }

    val issuedAt = readU32(payload, 7)
    val expiresAt = readU32(payload, 11)
    val nowSec = nowMs / 1000
    if (expiresAt <= issuedAt) return ControlFrameResult.Err("expiresAt not after issuedAt")
    if (expiresAt - issuedAt > type.maxLifetimeSec) {
        return ControlFrameResult.Err("lifetime exceeds per-type maximum")
    }
    if (expiresAt < nowSec) return ControlFrameResult.Err("control frame expired")

    if (!Signer.verifyMessage(pubkey, ControlFrame.signBytes(payload), sig)) {
        return ControlFrameResult.Err("signature invalid")
    }

    val targetNodeId = payload.copyOfRange(1, 5)
    val issuerNodeId = ControlFrame.nodeIdOf(pubkey)
    val pubkeyHex = ControlFrame.toHex(pubkey)

    if (type == ControlType.SELF_CANCEL) {
        // Only the victim's own key may cancel its SOS: issuer node id must be the target.
        if (!issuerNodeId.contentEquals(targetNodeId)) {
            return ControlFrameResult.Err("self-cancel issuer is not the target")
        }
    } else {
        if (backendPublicKeyHex.isNullOrEmpty()) {
            return ControlFrameResult.Err("backend key not pinned")
        }
        if (pubkeyHex != backendPublicKeyHex.lowercase()) {
            return ControlFrameResult.Err("issuer is not the pinned backend")
        }
    }

    return ControlFrameResult.Ok(
        VerifiedControlFrame(
            type = type,
            targetNodeId = targetNodeId,
            targetNodeIdHex = ControlFrame.toHex(targetNodeId),
            targetMsgId = readU16(payload, 5),
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            ctrlMsgId = readU16(payload, 15),
            issuerPublicKey = pubkey,
            issuerPublicKeyHex = pubkeyHex,
            issuerNodeIdHex = ControlFrame.toHex(issuerNodeId),
            signature = sig,
            signatureHex = ControlFrame.toHex(sig),
            payload = payload,
            frame = frame,
        )
    )
}

private fun readU16(b: ByteArray, off: Int): Int =
    (((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF))

private fun readU32(b: ByteArray, off: Int): Long =
    ((b[off].toInt() and 0xFF).toLong() shl 24) or
        ((b[off + 1].toInt() and 0xFF).toLong() shl 16) or
        ((b[off + 2].toInt() and 0xFF).toLong() shl 8) or
        (b[off + 3].toInt() and 0xFF).toLong()
