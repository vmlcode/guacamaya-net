package net.guacamaya.backend

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the Kotlin official-record verifier against a real backend-signed record
 * (captured from a running server with a fixed BACKEND_PRIVATE_KEY_HEX, so the pubkey,
 * id and sig below are reproducible). Proves the canonical content + Ed25519 path
 * matches `@guacamaya/shared` verifyRecordSignature byte-for-byte.
 */
class OfficialRecordVerifierTest {

    // From GET /pubkey with BACKEND_PRIVATE_KEY_HEX=11..11.
    private val backendPubkey = "d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737"

    private val body =
        """[{"channel":"alertas","timestamp":1782658760930,"ttl":3,"author":"backend","payload":{"title":"Sismo M6.1","severity":"alta","zona":"Centro"},"verified":true,"id":"923169ac45d5d7c74de385efb2b630e511a69e67f3ebb401e2eb34fa2fa406d9","sig":"f70d69bec71c4eec6fd3049764721950b37a3d6831915b6d7a749d05a20bfb84803e8205f0cc0e008ade196fc8176307af10aa5173011b31cdf45b9567fa270e"}]"""

    private fun record() = RecordJson.parseRecords(body).single()

    @Test fun `genuine backend-signed record verifies`() {
        assertTrue(OfficialRecordVerifier.verify(record(), backendPubkey))
    }

    @Test fun `tampered payload is rejected (content no longer hashes to id)`() {
        val r = record().copy(payloadRaw = """{"title":"Sismo M6.1","severity":"baja","zona":"Centro"}""")
        assertFalse(OfficialRecordVerifier.verify(r, backendPubkey))
    }

    @Test fun `tampered metadata is rejected`() {
        assertFalse(OfficialRecordVerifier.verify(record().copy(ttl = 4), backendPubkey))
        assertFalse(OfficialRecordVerifier.verify(record().copy(timestamp = 1782658760931L), backendPubkey))
    }

    @Test fun `wrong backend pubkey is rejected`() {
        val otherPubkey = "0000000000000000000000000000000000000000000000000000000000000000"
        assertFalse(OfficialRecordVerifier.verify(record(), otherPubkey))
    }

    @Test fun `missing signature is rejected`() {
        assertFalse(OfficialRecordVerifier.verify(record().copy(sig = null), backendPubkey))
    }

    @Test fun `id that does not match content is rejected even with valid-looking sig`() {
        // swap in a different id; content hash won't equal it
        assertFalse(OfficialRecordVerifier.verify(record().copy(id = "00".repeat(32)), backendPubkey))
    }
}
