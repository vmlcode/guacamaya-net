package net.guacamaya.aware

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Battery-aware NAN cadence. Urgent backlog gets denser discovery windows. */
data class NanDutyCycle(val listenMs: Long, val sleepMs: Long) {
    init { require(listenMs > 0 && sleepMs >= 0) }
    companion object {
        val IDLE = NanDutyCycle(5_000, 55_000)
        val ACTIVE = NanDutyCycle(8_000, 12_000)
        val URGENT = NanDutyCycle(12_000, 3_000)
        fun forBacklog(pending: Int, critical: Boolean = false) = when {
            critical -> URGENT
            pending > 0 -> ACTIVE
            else -> IDLE
        }
    }
}

/** Compact anti-entropy digest. IDs are represented by 64-bit SHA-256 prefixes;
 * a collision only causes a missed reconciliation round, never false acceptance. */
object NanDigestCodec {
    private const val VERSION: Byte = 1
    private const val HEADER = 2
    private const val HASH_BYTES = 8

    fun encode(ids: Collection<String>): ByteArray {
        val hashes = ids.asSequence().map(::prefix).distinct().sorted().take((AwareConfig.SSI_MAX - HEADER) / HASH_BYTES).toList()
        return ByteBuffer.allocate(HEADER + hashes.size * HASH_BYTES).put(VERSION).put(hashes.size.toByte()).also { b -> hashes.forEach(b::putLong) }.array()
    }

    fun missing(localIds: Collection<String>, remoteDigest: ByteArray): List<String> {
        val remote = decode(remoteDigest) ?: return emptyList()
        return localIds.filter { prefix(it) !in remote }
    }

    private fun decode(bytes: ByteArray): Set<Long>? {
        if (bytes.size < HEADER || bytes[0] != VERSION) return null
        val count = bytes[1].toInt() and 0xff
        if (bytes.size != HEADER + count * HASH_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes, HEADER, bytes.size - HEADER)
        return buildSet(count) { repeat(count) { add(buffer.long) } }
    }

    private fun prefix(id: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(id.toByteArray())).long
}
