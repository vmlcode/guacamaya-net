package net.guacamaya.backend.ws

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import kotlin.random.Random

/** WebSocket opcodes (RFC 6455 §5.2) we handle. */
object WsOpcode {
    const val TEXT = 0x1
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
}

/** One decoded WebSocket frame. Fragmentation is not supported (we assume FIN=1). */
data class WsMessage(val opcode: Int, val payload: ByteArray) {
    fun text(): String = String(payload, Charsets.UTF_8)
}

/**
 * Minimal RFC 6455 frame codec — just enough for a client that subscribes to a
 * backend channel and reads text events. No fragmentation, no extensions.
 *
 * Client→server frames MUST be masked (§5.3); server→client frames are unmasked.
 * Pure and testable: [encode] builds frames, [read] parses one off a stream.
 */
object WsFrame {

    /**
     * Encode a single FIN frame. [mask] = 4 bytes for client frames (required),
     * or null for server-style unmasked frames (used in tests).
     */
    fun encode(opcode: Int, payload: ByteArray, mask: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or (opcode and 0x0F)) // FIN + opcode
        val maskBit = if (mask != null) 0x80 else 0x00
        val len = payload.size
        when {
            len < 126 -> out.write(maskBit or len)
            len < 65_536 -> {
                out.write(maskBit or 126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                for (i in 7 downTo 0) out.write(((len.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        if (mask != null) {
            require(mask.size == 4) { "mask must be 4 bytes" }
            out.write(mask)
            val masked = ByteArray(len) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            out.write(masked)
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    /** Build a masked client text frame with a fresh random mask. */
    fun clientText(text: String): ByteArray =
        encode(WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8), Random.nextBytes(4))

    /** Build a masked client control frame (e.g. PONG echoing a PING payload). */
    fun clientControl(opcode: Int, payload: ByteArray): ByteArray =
        encode(opcode, payload, Random.nextBytes(4))

    /**
     * Read exactly one frame from [input]. Returns null on clean EOF. Unmasks if the
     * frame carries a mask (servers normally don't). Throws on a truncated frame.
     */
    fun read(input: InputStream): WsMessage? {
        val din = input as? DataInputStream ?: DataInputStream(input)
        val b0 = din.read()
        if (b0 == -1) return null
        val opcode = b0 and 0x0F
        val b1 = din.read()
        if (b1 == -1) return null
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        when (len) {
            126L -> len = (readByte(din).toLong() shl 8) or readByte(din).toLong()
            127L -> {
                len = 0
                repeat(8) { len = (len shl 8) or readByte(din).toLong() }
            }
        }
        val mask = if (masked) ByteArray(4).also { din.readFully(it) } else null
        val payload = ByteArray(len.toInt())
        din.readFully(payload)
        if (mask != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return WsMessage(opcode, payload)
    }

    private fun readByte(din: DataInputStream): Int {
        val b = din.read()
        if (b == -1) throw java.io.EOFException("truncated frame")
        return b
    }
}
