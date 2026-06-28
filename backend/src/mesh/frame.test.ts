import { expect, test, describe } from "bun:test";
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { getLocationId, crc16Ccitt, MESH_FRAME_LEN } from "@guacamaya/shared";
import { decodeAndVerifyFrame } from "./frame.js";

async function makeFrame(opts: {
  latE7: number;
  lonE7: number;
  tsUnix: number;
}): Promise<{ frameB64: string; pubkeyHex: string }> {
  const priv = ed.utils.randomPrivateKey();
  const pubkey = await ed.getPublicKeyAsync(priv);

  const payload = new Uint8Array(22);
  const dv = new DataView(payload.buffer);
  dv.setInt32(0, opts.latE7, false);
  dv.setInt32(4, opts.lonE7, false);
  dv.setUint32(8, opts.tsUnix, false);
  payload.set(sha256(pubkey).subarray(0, 4), 12);
  payload[16] = 0;
  payload[17] = 1;
  dv.setUint16(18, 42, false);
  dv.setUint16(20, crc16Ccitt(payload, 0, 20), false);

  const sig = await ed.signAsync(payload, priv);

  const frame = new Uint8Array(MESH_FRAME_LEN);
  frame.set(payload, 0);
  frame.set(pubkey, 22);
  frame.set(sig, 54);

  return {
    frameB64: Buffer.from(frame).toString("base64"),
    pubkeyHex: Buffer.from(pubkey).toString("hex"),
  };
}

describe("decodeAndVerifyFrame — location derivation", () => {
  test("a verified frame yields an authenticated LocationPoint", async () => {
    const { frameB64, pubkeyHex } = await makeFrame({
      latE7: 104900000,
      lonE7: -668700000,
      tsUnix: 1719500000,
    });

    const result = await decodeAndVerifyFrame(frameB64);
    expect(result.ok).toBe(true);
    if (!result.ok) return;

    expect(result.location).not.toBeNull();
    const loc = result.location!;
    expect(loc.deviceId).toBe(`device-${pubkeyHex}`);
    expect(loc.lat).toBeCloseTo(10.49, 6);
    expect(loc.lon).toBeCloseTo(-66.87, 6);
    expect(loc.timestamp).toBe(1719500000 * 1000);
    expect(loc.accuracy).toBeUndefined();
    expect(loc.id).toBe(
      getLocationId({ deviceId: loc.deviceId, lat: loc.lat, lon: loc.lon, timestamp: loc.timestamp }),
    );
  });

  test("the derived point dedups by id across mule re-uploads", async () => {
    const { frameB64 } = await makeFrame({ latE7: 104900000, lonE7: -668700000, tsUnix: 1719500000 });
    const a = await decodeAndVerifyFrame(frameB64);
    const b = await decodeAndVerifyFrame(frameB64);
    expect(a.ok && b.ok).toBe(true);
    if (!a.ok || !b.ok) return;
    expect(a.location!.id).toBe(b.location!.id);
  });

  test("a frame with no GPS fix (0,0) yields no location", async () => {
    const { frameB64 } = await makeFrame({ latE7: 0, lonE7: 0, tsUnix: 1719500000 });
    const result = await decodeAndVerifyFrame(frameB64);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.location).toBeNull();
  });

  test("a tampered frame is rejected (zero-trust gate)", async () => {
    const { frameB64 } = await makeFrame({ latE7: 104900000, lonE7: -668700000, tsUnix: 1719500000 });
    const bytes = Buffer.from(frameB64, "base64");
    bytes[0] = bytes[0]! ^ 0xff;
    const result = await decodeAndVerifyFrame(bytes.toString("base64"));
    expect(result.ok).toBe(false);
  });
});
