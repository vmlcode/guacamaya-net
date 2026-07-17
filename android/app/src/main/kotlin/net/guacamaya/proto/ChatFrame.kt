package net.guacamaya.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import net.guacamaya.crypto.Signer

data class ChatMessage(
    val senderNodeId: ByteArray,
    val messageId: Long,
    val timestampSeconds: Long,
    val text: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
)

data class ChatFragment(
    val ttl: Int,
    val senderNodeId: ByteArray,
    val messageId: Long,
    val timestampSeconds: Long,
    val index: Int,
    val count: Int,
    val totalLength: Int,
    val chunk: ByteArray,
    val publicKey: ByteArray,
    val signature: ByteArray,
)

object ChatCodec {
    const val VERSION = 1
    const val TYPE_MESSAGE = 1
    const val MAX_TEXT_BYTES = 160
    const val CHUNK_BYTES = 40
    const val MAX_FRAGMENTS = 4
    const val HEADER_BYTES = 23
    const val MAX_FRAME_BYTES = HEADER_BYTES + CHUNK_BYTES + 32 + 64
    private val DOMAIN = "guacamaya-chat-v1\u0000".toByteArray(Charsets.UTF_8)

    fun canonical(nodeId: ByteArray, messageId: Long, timestampSeconds: Long, text: ByteArray): ByteArray {
        require(nodeId.size == 4)
        require(text.isNotEmpty() && text.size <= MAX_TEXT_BYTES)
        return ByteBuffer.allocate(DOMAIN.size + 1 + 4 + 8 + 4 + 2 + text.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(DOMAIN).put(VERSION.toByte()).put(nodeId).putLong(messageId)
            .putInt(timestampSeconds.toInt()).putShort(text.size.toShort()).put(text).array()
    }

    fun fragment(message: ChatMessage, ttl: Int = 3): List<ByteArray> {
        require(ttl in 0..15)
        val count = (message.text.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        require(count in 1..MAX_FRAGMENTS)
        return (0 until count).map { index ->
            val from = index * CHUNK_BYTES
            val chunk = message.text.copyOfRange(from, minOf(from + CHUNK_BYTES, message.text.size))
            ByteBuffer.allocate(HEADER_BYTES + chunk.size + 32 + 64).order(ByteOrder.BIG_ENDIAN)
                .put(ttl.toByte()).put(VERSION.toByte()).put(TYPE_MESSAGE.toByte())
                .put(message.senderNodeId).putLong(message.messageId)
                .putInt(message.timestampSeconds.toInt()).put(index.toByte()).put(count.toByte())
                .put(message.text.size.toByte()).put(chunk.size.toByte()).put(chunk)
                .put(message.publicKey).put(message.signature).array()
        }
    }

    fun decode(frame: ByteArray): ChatFragment? {
      return try {
        if (frame.size !in (HEADER_BYTES + 1 + 96)..MAX_FRAME_BYTES) return null
        val b = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val ttl = b.get().toInt() and 0xff
        if ((b.get().toInt() and 0xff) != VERSION || (b.get().toInt() and 0xff) != TYPE_MESSAGE) return null
        val node = ByteArray(4).also(b::get)
        val id = b.long
        val ts = b.int.toLong() and 0xffffffffL
        val index = b.get().toInt() and 0xff
        val count = b.get().toInt() and 0xff
        val total = b.get().toInt() and 0xff
        val chunkSize = b.get().toInt() and 0xff
        if (ttl > 15 || count !in 1..MAX_FRAGMENTS || index >= count || total !in 1..MAX_TEXT_BYTES || chunkSize !in 1..CHUNK_BYTES) return null
        if (frame.size != HEADER_BYTES + chunkSize + 96) return null
        val chunk = ByteArray(chunkSize).also(b::get)
        val pub = ByteArray(32).also(b::get)
        val sig = ByteArray(64).also(b::get)
        ChatFragment(ttl, node, id, ts, index, count, total, chunk, pub, sig)
      } catch (_: RuntimeException) { null }
    }

    fun verify(message: ChatMessage, nowSeconds: Long): Boolean {
        if (kotlin.math.abs(nowSeconds - message.timestampSeconds) > 24 * 60 * 60) return false
        val bound = MessageDigest.getInstance("SHA-256").digest(message.publicKey).copyOfRange(0, 4)
        if (!bound.contentEquals(message.senderNodeId)) return false
        return Signer.verifyMessage(message.publicKey, canonical(message.senderNodeId, message.messageId, message.timestampSeconds, message.text), message.signature)
    }
}
