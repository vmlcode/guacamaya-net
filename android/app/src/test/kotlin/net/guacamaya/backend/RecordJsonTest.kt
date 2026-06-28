package net.guacamaya.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordJsonTest {

    // Verbatim body served by GET /channels/alertas/records (locked against a live backend).
    private val liveBody =
        """[{"channel":"alertas","timestamp":1782658760930,"ttl":3,"author":"backend","payload":{"title":"Sismo M6.1","severity":"alta","zona":"Centro"},"verified":true,"id":"923169ac45d5d7c74de385efb2b630e511a69e67f3ebb401e2eb34fa2fa406d9","sig":"f70d69bec71c4eec6fd3049764721950b37a3d6831915b6d7a749d05a20bfb84803e8205f0cc0e008ade196fc8176307af10aa5173011b31cdf45b9567fa270e"}]"""

    @Test fun `parses the live record and preserves raw payload verbatim`() {
        val recs = RecordJson.parseRecords(liveBody)
        assertEquals(1, recs.size)
        val r = recs[0]
        assertEquals("alertas", r.channel)
        assertEquals(1782658760930L, r.timestamp)
        assertEquals(3, r.ttl)
        assertEquals("backend", r.author)
        assertTrue(r.verified)
        assertEquals("923169ac45d5d7c74de385efb2b630e511a69e67f3ebb401e2eb34fa2fa406d9", r.id)
        // The payload span must be byte-identical to the wire (key order, no spaces).
        assertEquals("""{"title":"Sismo M6.1","severity":"alta","zona":"Centro"}""", r.payloadRaw)
    }

    @Test fun `empty array yields no records`() {
        assertEquals(0, RecordJson.parseRecords("[]").size)
        assertEquals(0, RecordJson.parseRecords("  [ ]  ").size)
    }

    @Test fun `null sig is parsed as null`() {
        val body =
            """[{"channel":"estoy-bien","timestamp":1,"ttl":0,"author":"device-ab","payload":{"x":1},"verified":false,"id":"deadbeef","sig":null}]"""
        val r = RecordJson.parseRecords(body)[0]
        assertNull(r.sig)
        assertEquals("""{"x":1}""", r.payloadRaw)
    }

    @Test fun `nested payload and braces inside strings are spanned correctly`() {
        val payload = """{"msg":"a } b ] c","nested":{"k":[1,2,{"z":"}"}]},"n":3}"""
        val body =
            """[{"channel":"alertas","timestamp":7,"ttl":1,"author":"backend","payload":$payload,"verified":true,"id":"ff","sig":"00"}]"""
        val r = RecordJson.parseRecords(body)[0]
        assertEquals(payload, r.payloadRaw)
        assertEquals(7L, r.timestamp)
    }

    @Test fun `tolerates unknown trailing keys`() {
        val body =
            """[{"channel":"alertas","timestamp":1,"ttl":0,"author":"backend","payload":{"a":1},"verified":true,"id":"ab","sig":"cd","extra":{"future":true}}]"""
        val r = RecordJson.parseRecords(body)[0]
        assertEquals("ab", r.id)
        assertEquals("""{"a":1}""", r.payloadRaw)
    }
}
