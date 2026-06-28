package net.guacamaya.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Pure Ed25519 sign/verify over a 22-byte payload. See docs/crypto.md.
 *
 * Library: BouncyCastle 1.78.1. Android's java.security "Ed25519" is only
 * available from API 33+, but minSdk = 26 (Wi-Fi Aware requirement).
 */
object Signer {

    const val SIGNATURE_SIZE = 64
    const val PUBLIC_KEY_SIZE = 32
    const val PRIVATE_KEY_SIZE = 32

    /** Sign the full 22-byte payload (CRC included). Returns 64-byte signature. */
    fun sign(privateKey: ByteArray, payload: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) {
            "private key must be $PRIVATE_KEY_SIZE bytes, got ${privateKey.size}"
        }
        require(payload.size == PayloadBytes.PAYLOAD_SIZE) {
            "payload must be ${PayloadBytes.PAYLOAD_SIZE} bytes, got ${payload.size}"
        }
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(privateKey, 0))
            update(payload, 0, payload.size)
        }
        return signer.generateSignature()
    }

    /** Verify a 64-byte signature against a 22-byte payload. Constant-time per BouncyCastle. */
    fun verify(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE) return false
        if (signature.size != SIGNATURE_SIZE) return false
        if (payload.size != PayloadBytes.PAYLOAD_SIZE) return false
        return try {
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(payload, 0, payload.size)
            }
            verifier.verifySignature(signature)
        } catch (t: Throwable) {
            false
        }
    }
}

/** Single source of truth for payload byte length. */
object PayloadBytes {
    const val PAYLOAD_SIZE = 22
}
