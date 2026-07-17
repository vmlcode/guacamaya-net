package net.guacamaya.proto

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.guacamaya.crypto.Signer

class ChatFrameTest {
    @Test fun `160 byte signed message fragments and verifies`() {
        val seed = ByteArray(32) { it.toByte() }
        val pub = Signer.derivePublicKey(seed)
        val node = MessageDigest.getInstance("SHA-256").digest(pub).copyOfRange(0, 4)
        val text = ByteArray(160) { ('a'.code + it % 26).toByte() }
        val canonical = ChatCodec.canonical(node, 42L, 1_000L, text)
        val message = ChatMessage(node, 42L, 1_000L, text, pub, Signer.signMessage(seed, canonical))
        val frames = ChatCodec.fragment(message)
        assertEquals(4, frames.size)
        val decoded = frames.map { assertNotNull(ChatCodec.decode(it)); ChatCodec.decode(it)!! }
        assertArrayEquals(text, decoded.sortedBy { it.index }.flatMap { it.chunk.asIterable() }.toByteArray())
        assertTrue(ChatCodec.verify(message, 1_000L))
    }
}
