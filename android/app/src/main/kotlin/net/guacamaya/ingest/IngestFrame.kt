package net.guacamaya.ingest

import java.util.Base64

/**
 * Rebuilds the 118-byte data-mule upload frame from a stored mesh record and
 * base64-encodes it for `POST /ingest`.
 *
 * The upload frame is the BLE service-data **without the leading hop-TTL byte**:
 *
 *   bytes 0..21    22 B signed payload
 *   bytes 22..53   32 B Ed25519 public key
 *   bytes 54..117  64 B Ed25519 signature
 *
 * The hop-TTL byte is mutable and unsigned, so it is intentionally never persisted
 * on the device — there is nothing to strip here, we simply do not prepend it. The
 * backend re-verifies every frame (CRC → pubkey-binding → Ed25519) before storing,
 * so this layout must stay byte-identical with `backend/src/mesh/frame.ts` and
 * `packages/shared/src/mesh/constants.ts`.
 */
object IngestFrame {

    const val PAYLOAD_LEN = 22
    const val PUBKEY_LEN = 32
    const val SIG_LEN = 64
    const val SIZE = PAYLOAD_LEN + PUBKEY_LEN + SIG_LEN // 118

    /** Concatenate payload‖pubkey‖sig into the canonical 118-byte frame. */
    fun encode(payload22: ByteArray, pubkey32: ByteArray, sig64: ByteArray): ByteArray {
        require(payload22.size == PAYLOAD_LEN) { "payload must be $PAYLOAD_LEN B, got ${payload22.size}" }
        require(pubkey32.size == PUBKEY_LEN) { "pubkey must be $PUBKEY_LEN B, got ${pubkey32.size}" }
        require(sig64.size == SIG_LEN) { "sig must be $SIG_LEN B, got ${sig64.size}" }
        val out = ByteArray(SIZE)
        System.arraycopy(payload22, 0, out, 0, PAYLOAD_LEN)
        System.arraycopy(pubkey32, 0, out, PAYLOAD_LEN, PUBKEY_LEN)
        System.arraycopy(sig64, 0, out, PAYLOAD_LEN + PUBKEY_LEN, SIG_LEN)
        return out
    }

    /** Standard (non-URL) base64 of the 118-byte frame — the wire form `/ingest` expects. */
    fun toBase64(payload22: ByteArray, pubkey32: ByteArray, sig64: ByteArray): String =
        Base64.getEncoder().encodeToString(encode(payload22, pubkey32, sig64))
}
