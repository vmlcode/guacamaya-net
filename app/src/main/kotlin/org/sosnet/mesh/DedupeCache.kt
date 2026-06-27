package org.sosnet.mesh

import java.util.LinkedHashMap
import kotlin.math.min

/**
 * LRU + TTL dedupe for the flood router. Keyed by `node_id || msg_id`.
 *
 * A frame is "new" if either (a) we've never seen its key, or (b) its last
 * sighting was more than [ttlMillis] ago. Capacity-bounded LRU evicts the
 * oldest entries under pressure.
 *
 * Thread-safe via a single coarse lock — contention is negligible at the rates
 * we care about (≤ few hundred frames/sec).
 */
class DedupeCache(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    // access-order = true so LRU eviction touches recency on read too.
    private val seen = object : LinkedHashMap<Key, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Long>?): Boolean {
            return size > capacity
        }
    }

    /** Returns true if this key is new (or its TTL expired). */
    fun admit(nodeId: ByteArray, msgId: Int): Boolean {
        val key = Key(nodeId, msgId)
        val ts = now()
        synchronized(lock) {
            val prev = seen[key]
            val fresh = prev == null || (ts - prev) > ttlMillis
            if (fresh) seen[key] = ts
            return fresh
        }
    }

    fun size(): Int = synchronized(lock) { seen.size }

    fun clear() = synchronized(lock) { seen.clear() }

    /** For tests — make the cache look full. */
    internal fun seed(nodeId: ByteArray, msgId: Int, ts: Long) {
        synchronized(lock) {
            seen[Key(nodeId, msgId)] = ts
        }
    }

    private class Key(val nodeId: ByteArray, val msgId: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Key
            if (msgId != other.msgId) return false
            if (!nodeId.contentEquals(other.nodeId)) return false
            return true
        }

        override fun hashCode(): Int {
            return 31 * msgId + nodeId.contentHashCode()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 1024
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1000L   // 5 min
    }
}
