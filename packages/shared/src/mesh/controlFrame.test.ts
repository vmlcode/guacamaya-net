import { describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import {
  CONTROL_FRAME_LEN,
  CONTROL_ON_WIRE_LEN,
  CONTROL_TYPE_BACKEND_RESOLVE,
  CONTROL_TYPE_DELIVERY,
  CONTROL_TYPE_SELF_CANCEL,
} from "./constants.js";
import {
  buildControlFrame,
  controlSignBytes,
  encodeControlPayload,
  verifyControlFrame,
} from "./controlFrame.js";
import { crc16Ccitt } from "./crc16.js";

// ---------------------------------------------------------------------------
// Golden vectors. Fixed keys, fixed fields, hex literals hardcoded so the
// Kotlin mirror (net.guacamaya.proto.ControlPayload / ControlPayloadTest)
// can assert byte-identical frames. Regenerating them is a wire-format break.
// ---------------------------------------------------------------------------
const VICTIM_PRIV = hexToBytes("11".repeat(32));
const VICTIM_PUB_HEX = "d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737";
const VICTIM_NODE_ID_HEX = "10ba682c"; // SHA-256(victim pubkey)[0..3]
const BACKEND_PRIV = hexToBytes("22".repeat(32));
const BACKEND_PUB_HEX = "a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f0";

const ISSUED_AT = 1_800_000_000; // unix seconds
const NOW_MS = 1_800_000_500_000; // inside every vector's validity window

const SELF_CANCEL_FIELDS = {
  type: CONTROL_TYPE_SELF_CANCEL,
  targetNodeId: hexToBytes(VICTIM_NODE_ID_HEX),
  targetMsgId: 0x1234,
  issuedAt: ISSUED_AT,
  expiresAt: ISSUED_AT + 3_600,
  ctrlMsgId: 0xbeef,
} as const;

const GOLDEN_SELF_CANCEL_PAYLOAD_HEX = "0310ba682c12346b49d2006b49e010beef0000006621";
const GOLDEN_SELF_CANCEL_FRAME_HEX =
  "0310ba682c12346b49d2006b49e010beef0000006621" +
  VICTIM_PUB_HEX +
  "e307f2b2fa94ef603e1fce5729a8460ecfbefa2bd4f32b7bc60cdcdf842eda04feb45b34df0679f04aa0b13b6d8d4f5b4e2c65bab1b910c1120725b8b3663f0f";

const GOLDEN_BACKEND_RESOLVE_FRAME_HEX =
  "0210ba682c12346b49d2006b4b238000420000008e61" +
  BACKEND_PUB_HEX +
  "7477a73067adaab04fc091f8712d8c6091ab469399cbf6977f938c3f5a3d48981c78b446f48a332365fdb4b296c2f955d6bfbc1148c8792115c5bf1f2ed7db01";

const GOLDEN_DELIVERY_FRAME_HEX =
  "0110ba682c12346b49d2006b530c807a5c000000f510" +
  BACKEND_PUB_HEX +
  "afdb6fc106e3d2d0692112d8368fd5bebcbd17a8555833c124889e5be2156d46a35946206073e6ba60c239677c393f178c3de73eff96d6c3dc21cd636cdf310b";

/** Signs an arbitrary (possibly invalid) payload with the control domain. */
async function frameFor(payload: Uint8Array, privateKey: Uint8Array): Promise<Uint8Array> {
  const pubkey = await ed.getPublicKeyAsync(privateKey);
  const sig = await ed.signAsync(controlSignBytes(payload), privateKey);
  const frame = new Uint8Array(CONTROL_FRAME_LEN);
  frame.set(payload, 0);
  frame.set(pubkey, 22);
  frame.set(sig, 54);
  return frame;
}

/** Rewrites the trailing CRC so a mutated payload still passes the CRC stage. */
function fixCrc(payload: Uint8Array): Uint8Array {
  const out = new Uint8Array(payload);
  new DataView(out.buffer).setUint16(20, crc16Ccitt(out, 0, 20), false);
  return out;
}

describe("control frame v1 golden vectors", () => {
  test("self-cancel build matches golden bytes and verifies", async () => {
    const frame = await buildControlFrame({ ...SELF_CANCEL_FIELDS }, VICTIM_PRIV);
    expect(bytesToHex(frame)).toBe(GOLDEN_SELF_CANCEL_FRAME_HEX);
    expect(bytesToHex(encodeControlPayload({ ...SELF_CANCEL_FIELDS }))).toBe(GOLDEN_SELF_CANCEL_PAYLOAD_HEX);

    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    if (!res.ok) throw new Error(res.reason);
    expect(res.control.type).toBe(CONTROL_TYPE_SELF_CANCEL);
    expect(res.control.targetNodeIdHex).toBe(VICTIM_NODE_ID_HEX);
    expect(res.control.targetMsgId).toBe(0x1234);
    expect(res.control.issuedAt).toBe(ISSUED_AT);
    expect(res.control.expiresAt).toBe(ISSUED_AT + 3_600);
    expect(res.control.ctrlMsgId).toBe(0xbeef);
    expect(res.control.issuerPublicKeyHex).toBe(VICTIM_PUB_HEX);
    expect(res.control.issuerNodeIdHex).toBe(VICTIM_NODE_ID_HEX);
  });

  test("backend-resolve build matches golden bytes and verifies against the pin", async () => {
    const frame = await buildControlFrame({
      type: CONTROL_TYPE_BACKEND_RESOLVE,
      targetNodeId: hexToBytes(VICTIM_NODE_ID_HEX),
      targetMsgId: 0x1234,
      issuedAt: ISSUED_AT,
      expiresAt: ISSUED_AT + 86_400,
      ctrlMsgId: 0x0042,
    }, BACKEND_PRIV);
    expect(bytesToHex(frame)).toBe(GOLDEN_BACKEND_RESOLVE_FRAME_HEX);

    const res = await verifyControlFrame(frame, { nowMs: NOW_MS, backendPublicKeyHex: BACKEND_PUB_HEX });
    if (!res.ok) throw new Error(res.reason);
    expect(res.control.type).toBe(CONTROL_TYPE_BACKEND_RESOLVE);
    expect(res.control.issuerPublicKeyHex).toBe(BACKEND_PUB_HEX);
  });

  test("delivery build matches golden bytes and verifies against the pin", async () => {
    const frame = await buildControlFrame({
      type: CONTROL_TYPE_DELIVERY,
      targetNodeId: hexToBytes(VICTIM_NODE_ID_HEX),
      targetMsgId: 0x1234,
      issuedAt: ISSUED_AT,
      expiresAt: ISSUED_AT + 7 * 86_400,
      ctrlMsgId: 0x7a5c,
    }, BACKEND_PRIV);
    expect(bytesToHex(frame)).toBe(GOLDEN_DELIVERY_FRAME_HEX);

    const res = await verifyControlFrame(frame, { nowMs: NOW_MS, backendPublicKeyHex: BACKEND_PUB_HEX });
    expect(res.ok).toBe(true);
  });

  test("119-byte on-wire frame is accepted by stripping the hop-TTL byte", async () => {
    const onWire = new Uint8Array(CONTROL_ON_WIRE_LEN);
    onWire[0] = 5; // mutable hop TTL, outside the signature
    onWire.set(hexToBytes(GOLDEN_SELF_CANCEL_FRAME_HEX), 1);
    const res = await verifyControlFrame(onWire, { nowMs: NOW_MS });
    if (!res.ok) throw new Error(res.reason);
    expect(bytesToHex(res.control.frame)).toBe(GOLDEN_SELF_CANCEL_FRAME_HEX);
  });
});

describe("control frame v1 reject cascade", () => {
  const goldenFrame = () => hexToBytes(GOLDEN_SELF_CANCEL_FRAME_HEX);

  test("bad length rejected", async () => {
    const res = await verifyControlFrame(goldenFrame().subarray(0, 100), { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: `bad control frame length 100 (need ${CONTROL_FRAME_LEN})` });
  });

  test("unknown type rejected before signature work", async () => {
    const payload = fixCrc(hexToBytes(GOLDEN_SELF_CANCEL_PAYLOAD_HEX));
    payload[0] = 0x04;
    const frame = await frameFor(fixCrc(payload), VICTIM_PRIV);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "unknown control type 0x4" });
  });

  test("non-zero reserved bytes rejected (no silent v2)", async () => {
    const payload = hexToBytes(GOLDEN_SELF_CANCEL_PAYLOAD_HEX);
    payload[18] = 0x01;
    const frame = await frameFor(fixCrc(payload), VICTIM_PRIV);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "reserved bytes not zero" });
  });

  test("crc mismatch rejected", async () => {
    const frame = goldenFrame();
    frame[21] ^= 0xff; // corrupt CRC low byte only
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "crc mismatch" });
  });

  test("expired frame rejected", async () => {
    const afterExpiry = (SELF_CANCEL_FIELDS.expiresAt + 1) * 1000;
    const res = await verifyControlFrame(goldenFrame(), { nowMs: afterExpiry });
    expect(res).toMatchObject({ ok: false, reason: "control frame expired" });
  });

  test("lifetime over per-type maximum rejected even with valid signature", async () => {
    // encodeControlPayload refuses to build this, so craft the payload by hand:
    // self-cancel capped at 24h, this one claims 48h.
    const payload = hexToBytes(GOLDEN_SELF_CANCEL_PAYLOAD_HEX);
    new DataView(payload.buffer).setUint32(11, ISSUED_AT + 48 * 3_600, false);
    const frame = await frameFor(fixCrc(payload), VICTIM_PRIV);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "lifetime exceeds per-type maximum" });
    expect(() => encodeControlPayload({ ...SELF_CANCEL_FIELDS, expiresAt: ISSUED_AT + 48 * 3_600 }))
      .toThrow("lifetime exceeds per-type maximum");
  });

  test("expiresAt not after issuedAt rejected", async () => {
    const payload = hexToBytes(GOLDEN_SELF_CANCEL_PAYLOAD_HEX);
    new DataView(payload.buffer).setUint32(11, ISSUED_AT, false);
    const frame = await frameFor(fixCrc(payload), VICTIM_PRIV);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "expiresAt not after issuedAt" });
  });

  test("SOS-style signature over the bare payload is rejected — domain separation", async () => {
    const payload = hexToBytes(GOLDEN_SELF_CANCEL_PAYLOAD_HEX);
    const pubkey = await ed.getPublicKeyAsync(VICTIM_PRIV);
    const bareSig = await ed.signAsync(payload, VICTIM_PRIV); // how SOS frames sign
    const frame = new Uint8Array(CONTROL_FRAME_LEN);
    frame.set(payload, 0);
    frame.set(pubkey, 22);
    frame.set(bareSig, 54);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "signature invalid" });
  });

  test("tampered payload with fixed CRC fails the signature", async () => {
    const frame = goldenFrame();
    const payload = fixCrc(frame.subarray(0, 22));
    new DataView(payload.buffer).setUint16(5, 0x9999, false); // retarget msgId
    frame.set(fixCrc(payload), 0);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "signature invalid" });
  });

  test("self-cancel signed by a key that is not the target is rejected", async () => {
    // Backend key signs a self-cancel aimed at the victim's node id: authorization must fail.
    const frame = await frameFor(hexToBytes(GOLDEN_SELF_CANCEL_PAYLOAD_HEX), BACKEND_PRIV);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "self-cancel issuer is not the target" });
  });

  test("backend types rejected without a pinned backend key", async () => {
    const res = await verifyControlFrame(hexToBytes(GOLDEN_BACKEND_RESOLVE_FRAME_HEX), { nowMs: NOW_MS });
    expect(res).toMatchObject({ ok: false, reason: "backend key not pinned" });
  });

  test("backend types rejected when the issuer is not the pinned backend", async () => {
    // Victim signs a backend-resolve payload: valid signature, wrong authority.
    const payload = hexToBytes(GOLDEN_BACKEND_RESOLVE_FRAME_HEX).subarray(0, 22);
    const frame = await frameFor(payload, VICTIM_PRIV);
    const res = await verifyControlFrame(frame, { nowMs: NOW_MS, backendPublicKeyHex: BACKEND_PUB_HEX });
    expect(res).toMatchObject({ ok: false, reason: "issuer is not the pinned backend" });

    const wrongPin = await verifyControlFrame(hexToBytes(GOLDEN_BACKEND_RESOLVE_FRAME_HEX), {
      nowMs: NOW_MS,
      backendPublicKeyHex: VICTIM_PUB_HEX,
    });
    expect(wrongPin).toMatchObject({ ok: false, reason: "issuer is not the pinned backend" });
  });

  test("encodeControlPayload validates field ranges", () => {
    expect(() => encodeControlPayload({ ...SELF_CANCEL_FIELDS, targetNodeId: new Uint8Array(3) }))
      .toThrow("targetNodeId must be 4 bytes");
    expect(() => encodeControlPayload({ ...SELF_CANCEL_FIELDS, targetMsgId: 0x10000 }))
      .toThrow("targetMsgId out of u16 range");
    expect(() => encodeControlPayload({ ...SELF_CANCEL_FIELDS, issuedAt: -1 }))
      .toThrow("issuedAt out of u32 range");
    expect(() => encodeControlPayload({ ...SELF_CANCEL_FIELDS, type: 0x04 as never }))
      .toThrow("unknown control type");
  });
});
