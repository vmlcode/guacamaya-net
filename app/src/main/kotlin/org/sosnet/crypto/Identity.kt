package org.sosnet.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable Ed25519 identity. One keypair per install, generated lazily on first launch.
 *
 * The 32-byte private seed is encrypted with AES-GCM using a key sealed in the Android
 * Keystore. The ciphertext lives in app-private SharedPreferences. The public key is
 * the durable node identity; node_id (4 B carried in every frame) is the first 4 B of
 * SHA-256(pubkey).
 *
 * See docs/crypto.md for the threat model and docs/protocol-flows.md Flow 0.
 */
class Identity private constructor(
    val privateKeySeed: ByteArray,
    val publicKey: ByteArray,
) {
    val nodeId: ByteArray = run {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        digest.copyOfRange(0, 4)
    }

    /** Sign and return the 64-byte Ed25519 signature. */
    fun sign(payload: ByteArray): ByteArray = Signer.sign(privateKeySeed, payload)

    companion object {
        private const val PREFS = "sosnet_identity"
        private const val SEED_KEY = "seed_blob"
        private const val IV_KEY = "seed_iv"
        private const val PUB_KEY = "seed_pub"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSERVICE_ALIAS = "sosnet_master"
        private const val GCM_TAG_BITS = 128

        /**
         * Load-or-create. Safe to call from any thread (SharedPreferences writes
         * serialized via commit()). Call from a background thread to be safe.
         */
        fun loadOrCreate(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existingPub = prefs.getString(PUB_KEY, null)
            val existingBlob = prefs.getString(SEED_KEY, null)
            val existingIv = prefs.getString(IV_KEY, null)

            return if (existingPub != null && existingBlob != null && existingIv != null) {
                val master = ensureMasterKey()
                val iv = android.util.Base64.decode(existingIv, android.util.Base64.NO_WRAP)
                val blob = android.util.Base64.decode(existingBlob, android.util.Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                val seed = cipher.doFinal(blob)
                Identity(seed, android.util.Base64.decode(existingPub, android.util.Base64.NO_WRAP))
            } else {
                val (seed, pub) = generate()
                persist(prefs, seed, pub)
                Identity(seed, pub)
            }
        }

        private fun generate(): Pair<ByteArray, ByteArray> {
            val gen = Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(SecureRandom()))
            }
            val kp = gen.generateKeyPair()
            val priv = (kp.private as Ed25519PrivateKeyParameters).encoded
            val pub = (kp.public as Ed25519PublicKeyParameters).encoded
            return priv to pub
        }

        private fun persist(
            prefs: android.content.SharedPreferences,
            seed: ByteArray,
            pub: ByteArray,
        ) {
            val master = ensureMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, master)
            }
            val iv = cipher.iv
            val blob = cipher.doFinal(seed)
            prefs.edit()
                .putString(SEED_KEY, android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP))
                .putString(IV_KEY, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .putString(PUB_KEY, android.util.Base64.encodeToString(pub, android.util.Base64.NO_WRAP))
                .apply()
        }

        private fun ensureMasterKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.getKey(KEYSERVICE_ALIAS, null)?.let { return it as SecretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEYSERVICE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            return gen.generateKey()
        }
    }
}
