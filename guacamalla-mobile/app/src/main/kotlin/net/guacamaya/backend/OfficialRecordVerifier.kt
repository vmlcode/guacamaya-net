package net.guacamaya.backend

import net.guacamaya.crypto.Signer
import java.security.MessageDigest

/**
 * Verifies a backend-signed official `ChannelRecord` (channel `alertas`/`refugios`/
 * `ayuda-medica`). Mirrors `verifyRecordSignature` in `@guacamaya/shared`:
 *
 *   content = "channel:timestamp:ttl:author:verified:" + payloadJson
 *   hash    = SHA-256(utf8(content))
 *   id      = hex(hash)            // backend sets record.id to this
 *   sig     = Ed25519.sign(hash)   // signed message is the 32-byte hash, not content
 *
 * Two independent checks, both required:
 *   1. hex(SHA-256(content)) == record.id  — binds payload→id, defeats a payload swap
 *      that reuses a validly-signed (id, sig) pair.
 *   2. Ed25519.verify(sig, hash, backendPubkey) — authenticity.
 *
 * `payloadJson` is [OfficialRecord.payloadRaw], the verbatim wire bytes — see
 * [RecordJson] for why it must not be re-serialized.
 *
 * This is a DISTINCT crypto path from the 22-byte mesh-frame signature
 * (`Signer.verify`): different message, different layout. Keep them separate.
 */
object OfficialRecordVerifier {

    fun verify(record: OfficialRecord, backendPubkeyHex: String): Boolean {
        val sigHex = record.sig ?: return false
        val pub = hexToBytes(backendPubkeyHex) ?: return false
        val sig = hexToBytes(sigHex) ?: return false
        if (pub.size != Signer.PUBLIC_KEY_SIZE || sig.size != Signer.SIGNATURE_SIZE) return false

        val content =
            "${record.channel}:${record.timestamp}:${record.ttl}:${record.author}:${record.verified}:${record.payloadRaw}"
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))

        // 1. content integrity: the hash of what we received must equal the claimed id.
        if (!toHex(hash).equals(record.id, ignoreCase = true)) return false

        // 2. authenticity: backend signed that hash.
        return Signer.verifyMessage(pub, hash, sig)
    }

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** Lenient hex→bytes; null on odd length or non-hex input. */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
