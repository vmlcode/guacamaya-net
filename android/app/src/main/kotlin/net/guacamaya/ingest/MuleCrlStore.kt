package net.guacamaya.ingest

import android.content.Context
import net.guacamaya.crypto.Signer
import java.security.MessageDigest

/** Verifies and monotonically installs backend CRLs received through /ingest. */
object MuleCrlStore {
    fun accept(context: Context, crl: MuleCrl, backendPublicKeyHex: String, now: Long = System.currentTimeMillis()): Boolean {
        if (crl.sequence < 0 || crl.issuedAt > now || crl.nextUpdate < now) return false
        if (crl.revokedPublicKeys.any { !it.matches(Regex("[0-9a-f]{64}")) }) return false
        val pub = hex(backendPublicKeyHex) ?: return false
        val sig = hex(crl.backendSignature) ?: return false
        val keys = crl.revokedPublicKeys.distinct().sorted().joinToString(",") { "\"$it\"" }
        val canonical = "{\"issuedAt\":${crl.issuedAt},\"nextUpdate\":${crl.nextUpdate},\"revokedPublicKeys\":[$keys],\"sequence\":${crl.sequence},\"version\":1}"
        val hash = MessageDigest.getInstance("SHA-256").digest("guacamaya.rescuer-crl.v1\n$canonical".toByteArray())
        if (!Signer.verifyMessage(pub, hash, sig)) return false
        val prefs = context.getSharedPreferences("guacamaya_crl", Context.MODE_PRIVATE)
        if (crl.sequence < prefs.getLong("sequence", -1)) return false
        prefs.edit().putLong("sequence", crl.sequence).putLong("next_update", crl.nextUpdate)
            .putStringSet("revoked", crl.revokedPublicKeys.toSet()).apply()
        return true
    }

    fun isRevoked(context: Context, publicKeyHex: String): Boolean =
        context.getSharedPreferences("guacamaya_crl", Context.MODE_PRIVATE)
            .getStringSet("revoked", emptySet())?.contains(publicKeyHex) == true

    private fun hex(value: String): ByteArray? {
        if (value.length % 2 != 0) return null
        return runCatching { ByteArray(value.length / 2) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }
}
