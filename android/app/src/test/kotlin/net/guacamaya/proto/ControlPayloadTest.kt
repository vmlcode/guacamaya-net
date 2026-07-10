package net.guacamaya.proto

import net.guacamaya.crypto.Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Android control-frame codec to the backend (`packages/shared/src/mesh/
 * controlFrame.ts`). The golden vectors below are reproduced *and* re-signed here:
 *   - [ControlPayload.encode] must yield the canonical payload bytes (layout + CRC);
 *   - [buildControlFrame] must reproduce the canonical 118 B frames (sign + assemble),
 *     proving the BouncyCastle signature equals noble's on the same domain-prefixed
 *     message;
 *   - [verifyControlFrame] must accept the canonical frames and reject each failure
 *     mode of the cascade with the exact reason string the backend emits.
 *
 * Keys: VICTIM = 0x11*32, BACKEND = 0x22*32 — same fixtures as
 * `packages/shared/src/mesh/controlFrame.test.ts` and `ingestControl.test.ts`.
 */
class ControlPayloadTest {

    private val victimPriv = ByteArray(32) { 0x11 }
    private val backendPriv = ByteArray(32) { 0x22 }
    private val otherPriv = ByteArray(32) { 0x33 }

    private val victimPubHex = "d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737"
    private val backendPubHex = "a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f0"
    private val victimNodeIdHex = "10ba682c"
    private val victimNodeId = ControlFrame.fromHex(victimNodeIdHex)

    private val issuedAt = 1_800_000_000L
    private val nowMs = 1_800_000_500_000L

    // ── Canonical golden payloads (layout + CRC) ─────────────────────────────────
    private val goldenSelfCancelPayloadHex = "0310ba682c12346b49d2006b49e010beef0000006621"
    private val goldenBackendResolvePayloadHex = "0210ba682c12346b49d2006b4b238000420000008e61"
    private val goldenDeliveryPayloadHex = "0110ba682c12346b49d2006b530c807a5c000000f510"

    // ── Canonical golden frames (payload + pubkey + sig) ─────────────────────────
    private val goldenSelfCancelFrameHex =
        goldenSelfCancelPayloadHex + victimPubHex +
            "e307f2b2fa94ef603e1fce5729a8460ecfbefa2bd4f32b7bc60cdcdf842eda04" +
            "feb45b34df0679f04aa0b13b6d8d4f5b4e2c65bab1b910c1120725b8b3663f0f"
    private val goldenBackendResolveFrameHex =
        goldenBackendResolvePayloadHex + backendPubHex +
            "7477a73067adaab04fc091f8712d8c6091ab469399cbf6977f938c3f5a3d4898" +
            "1c78b446f48a332365fdb4b296c2f955d6bfbc1148c8792115c5bf1f2ed7db01"
    private val goldenDeliveryFrameHex =
        goldenDeliveryPayloadHex + backendPubHex +
            "afdb6fc106e3d2d0692112d8368fd5bebcbd17a8555833c124889e5be2156d46" +
            "a35946206073e6ba60c239677c393f178c3de73eff96d6c3dc21cd636cdf310b"

    private fun selfCancel() = ControlPayload(
        ControlType.SELF_CANCEL, victimNodeId, 0x1234,
        issuedAt, issuedAt + 3_600L, 0xbeef,
    )

    private fun backendResolve() = ControlPayload(
        ControlType.BACKEND_RESOLVE, victimNodeId, 0x1234,
        issuedAt, issuedAt + 86_400L, 0x0042,
    )

    private fun delivery() = ControlPayload(
        ControlType.DELIVERY, victimNodeId, 0x1234,
        issuedAt, issuedAt + 604_800L, 0x7a5c,
    )

    // ── Key derivation parity with the backend (noble) ───────────────────────────

    @Test fun `victim pubkey matches backend fixture`() {
        assertEquals(victimPubHex, ControlFrame.toHex(Signer.derivePublicKey(victimPriv)))
    }

    @Test fun `backend pubkey matches backend fixture`() {
        assertEquals(backendPubHex, ControlFrame.toHex(Signer.derivePublicKey(backendPriv)))
    }

    @Test fun `node id is sha256 prefix of pubkey`() {
        assertEquals(victimNodeIdHex, ControlFrame.toHex(ControlFrame.nodeIdOf(ControlFrame.fromHex(victimPubHex))))
    }

    // ── encode: layout + CRC are byte-identical with the backend ─────────────────

    @Test fun `encode self-cancel matches golden payload`() {
        assertEquals(goldenSelfCancelPayloadHex, ControlFrame.toHex(selfCancel().encode()))
    }

    @Test fun `encode backend-resolve matches golden payload`() {
        assertEquals(goldenBackendResolvePayloadHex, ControlFrame.toHex(backendResolve().encode()))
    }

    @Test fun `encode delivery matches golden payload`() {
        assertEquals(goldenDeliveryPayloadHex, ControlFrame.toHex(delivery().encode()))
    }

    // ── buildControlFrame: sign + assemble reproduce the canonical frames ────────

    @Test fun `build self-cancel frame matches golden`() {
        assertEquals(goldenSelfCancelFrameHex, ControlFrame.toHex(buildControlFrame(selfCancel(), victimPriv)))
    }

    @Test fun `build backend-resolve frame matches golden`() {
        assertEquals(goldenBackendResolveFrameHex, ControlFrame.toHex(buildControlFrame(backendResolve(), backendPriv)))
    }

    @Test fun `build delivery frame matches golden`() {
        assertEquals(goldenDeliveryFrameHex, ControlFrame.toHex(buildControlFrame(delivery(), backendPriv)))
    }

    // ── verifyControlFrame: accept the canonical frames ──────────────────────────

    @Test fun `verify accepts golden self-cancel without backend pin`() {
        val r = verifyControlFrame(ControlFrame.fromHex(goldenSelfCancelFrameHex), nowMs, null)
        assertTrue(r is ControlFrameResult.Ok)
        val c = (r as ControlFrameResult.Ok).control
        assertEquals(ControlType.SELF_CANCEL, c.type)
        assertEquals(victimNodeIdHex, c.targetNodeIdHex)
        assertEquals(0x1234, c.targetMsgId)
        assertEquals(0xbeef, c.ctrlMsgId)
        assertEquals(victimNodeIdHex, c.issuerNodeIdHex) // self-bound
    }

    @Test fun `verify accepts golden backend-resolve with pinned backend`() {
        val r = verifyControlFrame(ControlFrame.fromHex(goldenBackendResolveFrameHex), nowMs, backendPubHex)
        assertTrue(r is ControlFrameResult.Ok)
        assertEquals(ControlType.BACKEND_RESOLVE, (r as ControlFrameResult.Ok).control.type)
        assertEquals(backendPubHex, r.control.issuerPublicKeyHex)
    }

    @Test fun `verify accepts golden delivery with pinned backend`() {
        val r = verifyControlFrame(ControlFrame.fromHex(goldenDeliveryFrameHex), nowMs, backendPubHex)
        assertTrue(r is ControlFrameResult.Ok)
        assertEquals(ControlType.DELIVERY, (r as ControlFrameResult.Ok).control.type)
    }

    @Test fun `verify strips leading hop ttl from 119-byte wire frame`() {
        val wire = ByteArray(ControlFrame.ON_WIRE_LEN)
        val frame = ControlFrame.fromHex(goldenSelfCancelFrameHex)
        wire[0] = 7 // hop TTL
        System.arraycopy(frame, 0, wire, 1, frame.size)
        val r = verifyControlFrame(wire, nowMs, null)
        assertTrue(r is ControlFrameResult.Ok)
    }

    // ── round trip ───────────────────────────────────────────────────────────────

    @Test fun `decode round-trips encode for every type`() {
        for (p in listOf(selfCancel(), backendResolve(), delivery())) {
            val back = ControlPayload.decode(p.encode())
            assertEquals(p.type, back.type)
            assertEquals(p.targetMsgId, back.targetMsgId)
            assertEquals(p.issuedAt, back.issuedAt)
            assertEquals(p.expiresAt, back.expiresAt)
            assertEquals(p.ctrlMsgId, back.ctrlMsgId)
        }
    }

    // ── reject cascade (cheapest first) ──────────────────────────────────────────

    @Test fun `reject wrong length`() {
        val r = verifyControlFrame(ByteArray(50), nowMs, null)
        assertEquals("bad control frame length 50 (need 118)", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject unknown type before crc`() {
        val bytes = ControlFrame.fromHex(goldenSelfCancelFrameHex)
        bytes[0] = 0x09 // corrupts CRC too, but type check fires first
        val r = verifyControlFrame(bytes, nowMs, null)
        assertEquals("unknown control type 0x9", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject non-zero reserved bytes before crc`() {
        val bytes = ControlFrame.fromHex(goldenSelfCancelFrameHex)
        bytes[17] = 0x01 // covered by CRC, but reserved check fires first
        val r = verifyControlFrame(bytes, nowMs, null)
        assertEquals("reserved bytes not zero", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject crc mismatch`() {
        val bytes = ControlFrame.fromHex(goldenSelfCancelFrameHex)
        bytes[5] = (bytes[5].toInt() xor 0xFF).toByte() // targetMsgId hi byte
        val r = verifyControlFrame(bytes, nowMs, null)
        assertEquals("crc mismatch", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject expired frame`() {
        val expired = ControlPayload(
            ControlType.SELF_CANCEL, victimNodeId, 0x1234,
            (nowMs / 1000) - 7_200L, (nowMs / 1000) - 3_600L, 0xbeef,
        )
        val r = verifyControlFrame(buildControlFrame(expired, victimPriv), nowMs, null)
        assertEquals("control frame expired", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject lifetime exceeding per-type maximum`() {
        // Hand-crafted (encode refuses this): self-cancel lifetime 100_000s > 86_400 max.
        val crafted = craftFrame(0x03, victimNodeId, 0x1234, issuedAt, issuedAt + 100_000L, 0xbeef)
        val r = verifyControlFrame(crafted, nowMs, null)
        assertEquals("lifetime exceeds per-type maximum", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject expiresAt not after issuedAt`() {
        val crafted = craftFrame(0x03, victimNodeId, 0x1234, issuedAt, issuedAt, 0xbeef)
        val r = verifyControlFrame(crafted, nowMs, null)
        assertEquals("expiresAt not after issuedAt", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject bad signature`() {
        val bytes = ControlFrame.fromHex(goldenSelfCancelFrameHex)
        bytes[60] = (bytes[60].toInt() xor 0x01).toByte() // flip one sig byte
        val r = verifyControlFrame(bytes, nowMs, null)
        assertEquals("signature invalid", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject delivery without pinned backend key`() {
        val r = verifyControlFrame(ControlFrame.fromHex(goldenDeliveryFrameHex), nowMs, null)
        assertEquals("backend key not pinned", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject delivery signed by non-backend issuer`() {
        val forged = buildControlFrame(delivery(), victimPriv) // valid sig, wrong issuer
        val r = verifyControlFrame(forged, nowMs, backendPubHex)
        assertEquals("issuer is not the pinned backend", (r as ControlFrameResult.Err).reason)
    }

    @Test fun `reject self-cancel not signed by the target`() {
        val otherNodeId = ControlFrame.nodeIdOf(Signer.derivePublicKey(otherPriv))
        val misbound = ControlPayload(
            ControlType.SELF_CANCEL, otherNodeId, 0x1234, issuedAt, issuedAt + 3_600L, 0xbeef,
        )
        val r = verifyControlFrame(buildControlFrame(misbound, victimPriv), nowMs, null)
        assertEquals("self-cancel issuer is not the target", (r as ControlFrameResult.Err).reason)
    }

    // ── encode validation throws ─────────────────────────────────────────────────

    @Test fun `encode rejects targetNodeId of wrong size`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlPayload(ControlType.DELIVERY, ByteArray(3), 1, issuedAt, issuedAt + 60, 1)
        }
    }

    @Test fun `encode rejects lifetime exceeding maximum`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlPayload(ControlType.SELF_CANCEL, victimNodeId, 1, issuedAt, issuedAt + 100_000L, 1)
        }
    }

    @Test fun `encode rejects expiresAt not after issuedAt`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlPayload(ControlType.DELIVERY, victimNodeId, 1, issuedAt, issuedAt, 1)
        }
    }

    @Test fun `encode rejects out-of-range u16`() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlPayload(ControlType.DELIVERY, victimNodeId, 0x10000, issuedAt, issuedAt + 60, 1)
        }
    }

    // craft helpers bypass [ControlPayload] validation to build malicious wire bytes
    // for the defense-in-depth reject cases (lifetime / ordering) that a legit encode
    // can never produce. Rejects fire before the signature check, so pubkey+sig are dummy.
    private fun craftFrame(
        type: Int, targetNodeId: ByteArray, msgId: Int,
        issuedAt: Long, expiresAt: Long, ctrlMsgId: Int,
    ): ByteArray {
        require(targetNodeId.size == 4)
        val payload = ByteArray(ControlFrame.PAYLOAD_LEN)
        payload[0] = type.toByte()
        System.arraycopy(targetNodeId, 0, payload, 1, 4)
        putU16(payload, 5, msgId)
        putU32(payload, 7, issuedAt)
        putU32(payload, 11, expiresAt)
        putU16(payload, 15, ctrlMsgId)
        // 17..19 stay zero
        val crc = Crc16.ccitt(payload, 0, 20) and 0xFFFF
        putU16(payload, 20, crc)
        val frame = ByteArray(ControlFrame.FRAME_LEN)
        System.arraycopy(payload, 0, frame, 0, ControlFrame.PAYLOAD_LEN)
        return frame
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
