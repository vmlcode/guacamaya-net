package net.guacamaya.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Round-trip + tamper tests for the Ed25519 signer over a 22-byte payload.
 *
 * JVM unit test — BouncyCastle runs unmodified on Robolectric-free JVM.
 */
class SignerTest {

    private val rng = SecureRandom()

    private fun newKeyPair(): Pair<ByteArray, ByteArray> {
        // Mirror Identity.generate() without touching Android Keystore.
        val gen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator().apply {
            init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(rng))
        }
        val kp = gen.generateKeyPair()
        val priv = (kp.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded
        val pub = (kp.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
        return priv to pub
    }

    private fun randomPayload(): ByteArray = ByteArray(22).also { rng.nextBytes(it) }

    @Test fun `round trip sign verify succeeds`() {
        val (priv, pub) = newKeyPair()
        val payload = randomPayload()
        val sig = Signer.sign(priv, payload)
        assertTrue("signature must verify", Signer.verify(pub, payload, sig))
    }

    @Test fun `deterministic signature`() {
        val (priv, pub) = newKeyPair()
        val payload = randomPayload()
        val s1 = Signer.sign(priv, payload)
        val s2 = Signer.sign(priv, payload)
        assertArrayEquals("Ed25519 is deterministic", s1, s2)
        assertTrue(Signer.verify(pub, payload, s1))
    }

    @Test fun `tampered payload is rejected`() {
        val (priv, pub) = newKeyPair()
        val payload = randomPayload()
        val sig = Signer.sign(priv, payload)

        val tampered = payload.copyOf()
        tampered[5] = (tampered[5].toInt() xor 0x01).toByte()

        assertFalse("flipped bit must break verify", Signer.verify(pub, tampered, sig))
    }

    @Test fun `tampered signature is rejected`() {
        val (priv, pub) = newKeyPair()
        val payload = randomPayload()
        val sig = Signer.sign(priv, payload)

        val badSig = sig.copyOf()
        badSig[0] = (badSig[0].toInt() xor 0x80).toByte()

        assertFalse("flipped sig bit must break verify", Signer.verify(pub, payload, badSig))
    }

    @Test fun `wrong key is rejected`() {
        val (priv1, _) = newKeyPair()
        val (_, pub2) = newKeyPair()
        val payload = randomPayload()
        val sig = Signer.sign(priv1, payload)
        assertFalse("verify with unrelated pubkey must fail", Signer.verify(pub2, payload, sig))
    }

    @Test fun `wrong sizes are rejected without crashing`() {
        val (priv, pub) = newKeyPair()
        val payload = randomPayload()
        val sig = Signer.sign(priv, payload)

        // Truncated payload
        assertFalse(Signer.verify(pub, payload.copyOf(21), sig))
        // Truncated signature
        assertFalse(Signer.verify(pub, payload, sig.copyOf(63)))
        // Truncated pubkey
        assertFalse(Signer.verify(pub.copyOf(31), payload, sig))
    }
}
