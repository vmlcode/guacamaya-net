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
        """[{"channel":"alertas","timestamp":1782658760930,"ttl":3,"author":"backend","payload":{"severity":"alta","title":"Sismo M6.1","zona":"Centro"},"verified":true,"id":"b610be875897946fe22acc9dedc009acab8d16c03b43125d8f08a48ee54c4d85","sig":"d57f70c07a5f51785ab1a9c7813c89e663f68b7b25a2d017b70a048d17d805d4216e3403ef68c3989b5a1fa308274a9f1b929e83b2868013bc0053e51cf2c400"}]"""

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
