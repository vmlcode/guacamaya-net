import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { crc16Ccitt } from "./crc16.js";
import {
  FOUND_FRAME_LEN,
  FOUND_ON_WIRE_LEN,
  FOUND_PAYLOAD_LEN,
  FOUND_PAYLOAD_OFF,
  FOUND_PUBKEY_OFF,
  FOUND_SIGN_DOMAIN,
  FOUND_SIG_OFF,
  MESH_PUBKEY_LEN,
  MESH_SIG_LEN,
} from "./constants.js";

/**
 * Found payload v1 ("Encontrado") — 22 bytes, big-endian, byte-identical with
 * net.guacamaya.proto.FoundPayload (Android). See constants.ts for the byte map.
 *
 * Signature is domain-separated: Ed25519 over UTF8(FOUND_SIGN_DOMAIN) || payload22
 * — never over the bare payload, so SOS/control/found signatures are never
 * interchangeable.
 */

export interface FoundPayloadFields {
  /** Finder latitude × 1e7 (int32). */
  latE7: number;
  /** Finder longitude × 1e7 (int32). */
  lonE7: number;
  /** Unix seconds. */
  tsUnix: number;
  /** SHA-256(victim pubkey)[0..3], 4 bytes. */
  targetNodeId: Uint8Array;
  targetMsgId: number;
  /** CSPRNG per found report (dedupe entropy). */
  foundMsgId: number;
}

export interface VerifiedFoundFrame extends FoundPayloadFields {
  lat: number;
  lon: number;
  targetNodeIdHex: string;
  finderPublicKeyHex: string;
  /** SHA-256(finder pubkey)[0..3] as hex. */
  finderNodeIdHex: string;
  signatureHex: string;
  payload: Uint8Array;
  /** Canonical 118-byte upload frame (TTL stripped). */
  frame: Uint8Array;
}

export type FoundFrameResult =
  | { ok: true; found: VerifiedFoundFrame }
  | { ok: false; reason: string };

function checkU16(value: number, name: string): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffff) throw new Error(`${name} out of u16 range`);
}

function checkU32(value: number, name: string): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) throw new Error(`${name} out of u32 range`);
}

function checkI32(value: number, name: string): void {
  if (!Number.isInteger(value) || value < -0x80000000 || value > 0x7fffffff) throw new Error(`${name} out of i32 range`);
}

/** Encodes the 22-byte found payload, computing the trailing CRC. Throws on invalid fields. */
export function encodeFoundPayload(fields: FoundPayloadFields): Uint8Array {
  if (fields.targetNodeId.length !== 4) throw new Error("targetNodeId must be 4 bytes");
  checkI32(fields.latE7, "latE7");
  checkI32(fields.lonE7, "lonE7");
  checkU32(fields.tsUnix, "tsUnix");
  checkU16(fields.targetMsgId, "targetMsgId");
  checkU16(fields.foundMsgId, "foundMsgId");
  const payload = new Uint8Array(FOUND_PAYLOAD_LEN);
  const dv = new DataView(payload.buffer);
  dv.setInt32(0, fields.latE7, false);
  dv.setInt32(4, fields.lonE7, false);
  dv.setUint32(8, fields.tsUnix, false);
  payload.set(fields.targetNodeId, 12);
  dv.setUint16(16, fields.targetMsgId, false);
  dv.setUint16(18, fields.foundMsgId, false);
  dv.setUint16(20, crc16Ccitt(payload, 0, 20), false);
  return payload;
}

/** The exact bytes the Ed25519 signature covers: UTF8 domain prefix + payload. */
export function foundSignBytes(payload: Uint8Array): Uint8Array {
  const domain = new TextEncoder().encode(FOUND_SIGN_DOMAIN);
  const out = new Uint8Array(domain.length + payload.length);
  out.set(domain, 0);
  out.set(payload, domain.length);
  return out;
}

/** Builds the canonical 118-byte upload frame: payload22 + pubkey32 + sig64. */
export async function buildFoundFrame(
  fields: FoundPayloadFields,
  privateKey: Uint8Array,
): Promise<Uint8Array> {
  const payload = encodeFoundPayload(fields);
  const pubkey = await ed.getPublicKeyAsync(privateKey);
  const sig = await ed.signAsync(foundSignBytes(payload), privateKey);
  const frame = new Uint8Array(FOUND_FRAME_LEN);
  frame.set(payload, FOUND_PAYLOAD_OFF);
  frame.set(pubkey, FOUND_PUBKEY_OFF);
  frame.set(sig, FOUND_SIG_OFF);
  return frame;
}

/**
 * Zero-trust verification cascade, cheapest first (mirrors the SOS/control
 * verifiers): size → CRC → GPS-fix sanity → Ed25519 (domain-prefixed).
 * Timestamp skew is intentionally omitted — a data mule may upload stale reports.
 * Target existence / proximity verdicts stay with the backend caller.
 */
export async function verifyFoundFrame(bytes: Uint8Array): Promise<FoundFrameResult> {
  let frame = bytes;
  if (frame.length === FOUND_ON_WIRE_LEN) frame = frame.subarray(1);
  if (frame.length !== FOUND_FRAME_LEN) {
    return { ok: false, reason: `bad found frame length ${bytes.length} (need ${FOUND_FRAME_LEN})` };
  }

  const payload = frame.subarray(FOUND_PAYLOAD_OFF, FOUND_PAYLOAD_OFF + FOUND_PAYLOAD_LEN);
  const pubkey = frame.subarray(FOUND_PUBKEY_OFF, FOUND_PUBKEY_OFF + MESH_PUBKEY_LEN);
  const sig = frame.subarray(FOUND_SIG_OFF, FOUND_SIG_OFF + MESH_SIG_LEN);
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);

  if (dv.getUint16(20, false) !== crc16Ccitt(payload, 0, 20)) {
    return { ok: false, reason: "crc mismatch" };
  }

  const latE7 = dv.getInt32(0, false);
  const lonE7 = dv.getInt32(4, false);
  const lat = latE7 / 1e7;
  const lon = lonE7 / 1e7;
  const hasFix =
    lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(latE7 === 0 && lonE7 === 0);
  if (!hasFix) {
    // A found report is a proximity claim; without the finder's fix there is
    // nothing to validate against, so the frame is useless — reject it.
    return { ok: false, reason: "no gps fix" };
  }

  let sigValid = false;
  try {
    sigValid = await ed.verifyAsync(sig, foundSignBytes(payload), pubkey);
  } catch {
    sigValid = false;
  }
  if (!sigValid) return { ok: false, reason: "signature invalid" };

  const targetNodeId = payload.subarray(12, 16);
  return {
    ok: true,
    found: {
      latE7,
      lonE7,
      lat,
      lon,
      tsUnix: dv.getUint32(8, false),
      targetNodeId: new Uint8Array(targetNodeId),
      targetNodeIdHex: bytesToHex(targetNodeId),
      targetMsgId: dv.getUint16(16, false),
      foundMsgId: dv.getUint16(18, false),
      finderPublicKeyHex: bytesToHex(pubkey),
      finderNodeIdHex: bytesToHex(sha256(pubkey).subarray(0, 4)),
      signatureHex: bytesToHex(sig),
      payload: new Uint8Array(payload),
      frame: new Uint8Array(frame),
    },
  };
}
