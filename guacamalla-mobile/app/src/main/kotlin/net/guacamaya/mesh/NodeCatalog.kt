package net.guacamaya.mesh

import net.guacamaya.proto.SosType

/** One row per node_id — latest heartbeat/SOS for UI (map, radar, recibidos). */
object NodeCatalog {

    /** Messages are recent-first; keep first sighting per node_id. */
    fun latestByNode(messages: List<MessageEntity>): List<MessageEntity> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<MessageEntity>(minOf(messages.size, 256))
        for (msg in messages) {
            val key = msg.nodeId.toHexKey()
            if (seen.add(key)) out.add(msg)
        }
        return out
    }

    fun formatLastHeartbeat(receivedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val sec = ((nowMillis - receivedAtMillis) / 1000).coerceAtLeast(0)
        return when {
            sec < 15 -> "ahora"
            sec < 60 -> "hace ${sec}s"
            sec < 3_600 -> "hace ${sec / 60} min"
            sec < 86_400 -> "hace ${sec / 3_600} h"
            else -> {
                val fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                fmt.format(java.util.Date(receivedAtMillis))
            }
        }
    }

    fun signalKind(msg: MessageEntity): String = when {
        msg.critical -> "SOS"
        SosType.fromCode(msg.sosType) == SosType.DISTRESS -> "SOS"
        else -> "heartbeat"
    }

    fun ByteArray.toHexKey(): String = joinToString("") { "%02x".format(it) }
}
