import { describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { crc16Ccitt } from "./crc16.js";
import { FOUND_FRAME_LEN, FOUND_SIG_OFF, FOUND_PUBKEY_OFF } from "./constants.js";
import {
  buildFoundFrame,
  encodeFoundPayload,
  verifyFoundFrame,
  type FoundPayloadFields,
} from "./foundFrame.js";
import { haversineMeters } from "../geo.js";

const sk = new Uint8Array(32).fill(7);

function fields(overrides: Partial<FoundPayloadFields> = {}): FoundPayloadFields {
  return {
    latE7: 104_003_000, // 10.4003
    lonE7: -669_000_000, // -66.9
    tsUnix: 1_750_000_000,
    targetNodeId: new Uint8Array([0xaa, 0xbb, 0xcc, 0xdd]),
    targetMsgId: 4242,
    foundMsgId: 777,
    ...overrides,
  };
}

describe("encodeFoundPayload", () => {
  test("byte layout is big-endian at the documented offsets with a valid CRC", () => {
    const p = encodeFoundPayload(fields());
    expect(p.length).toBe(22);
    const dv = new DataView(p.buffer, p.byteOffset, p.byteLength);
    expect(dv.getInt32(0, false)).toBe(104_003_000);
    expect(dv.getInt32(4, false)).toBe(-669_000_000);
    expect(dv.getUint32(8, false)).toBe(1_750_000_000);
    expect([...p.subarray(12, 16)]).toEqual([0xaa, 0xbb, 0xcc, 0xdd]);
    expect(dv.getUint16(16, false)).toBe(4242);
    expect(dv.getUint16(18, false)).toBe(777);
    expect(dv.getUint16(20, false)).toBe(crc16Ccitt(p, 0, 20));
  });

  test("rejects a targetNodeId that is not 4 bytes", () => {
    expect(() => encodeFoundPayload(fields({ targetNodeId: new Uint8Array(3) }))).toThrow();
  });
});

describe("verifyFoundFrame", () => {
  test("round-trips a built frame", async () => {
    const frame = await buildFoundFrame(fields(), sk);
    expect(frame.length).toBe(FOUND_FRAME_LEN);
    const res = await verifyFoundFrame(frame);
    if (!res.ok) throw new Error(res.reason);
    expect(res.found.lat).toBeCloseTo(10.4003, 6);
    expect(res.found.lon).toBeCloseTo(-66.9, 6);
    expect(res.found.targetNodeIdHex).toBe("aabbccdd");
    expect(res.found.targetMsgId).toBe(4242);
    expect(res.found.foundMsgId).toBe(777);
    const pub = await ed.getPublicKeyAsync(sk);
    expect(res.found.finderPublicKeyHex).toBe(bytesToHex(pub));
    expect(res.found.finderNodeIdHex).toBe(bytesToHex(sha256(pub).subarray(0, 4)));
  });

  test("accepts the 119-byte on-wire form by stripping the leading TTL byte", async () => {
    const frame = await buildFoundFrame(fields(), sk);
    const onWire = new Uint8Array(frame.length + 1);
    onWire[0] = 5; // hop TTL
    onWire.set(frame, 1);
    const res = await verifyFoundFrame(onWire);
    expect(res.ok).toBe(true);
  });

  test("rejects a tampered payload (crc mismatch)", async () => {
    const frame = await buildFoundFrame(fields(), sk);
    frame[0] ^= 0xff;
    const res = await verifyFoundFrame(frame);
    expect(res.ok).toBe(false);
  });

  test("rejects a signature made without the domain prefix", async () => {
    const payload = encodeFoundPayload(fields());
    const pub = await ed.getPublicKeyAsync(sk);
    const badSig = await ed.signAsync(payload, sk); // bare payload — wrong domain
    const frame = new Uint8Array(FOUND_FRAME_LEN);
    frame.set(payload, 0);
    frame.set(pub, FOUND_PUBKEY_OFF);
    frame.set(badSig, FOUND_SIG_OFF);
    const res = await verifyFoundFrame(frame);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toBe("signature invalid");
  });

  test("rejects a frame with no GPS fix (0,0)", async () => {
    const frame = await buildFoundFrame(fields({ latE7: 0, lonE7: 0 }), sk);
    const res = await verifyFoundFrame(frame);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toBe("no gps fix");
  });

  test("rejects a wrong-length frame", async () => {
    const res = await verifyFoundFrame(new Uint8Array(50));
    expect(res.ok).toBe(false);
  });
});

describe("haversineMeters", () => {
  test("~33 m for a 0.0003° latitude offset", () => {
    const d = haversineMeters(10.4, -66.9, 10.4003, -66.9);
    expect(d).toBeGreaterThan(25);
    expect(d).toBeLessThan(45);
  });

  test("~1.1 km for a 0.01° latitude offset", () => {
    const d = haversineMeters(10.4, -66.9, 10.41, -66.9);
    expect(d).toBeGreaterThan(1000);
    expect(d).toBeLessThan(1250);
  });
});
