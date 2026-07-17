import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { crc16Ccitt } from "./crc16.js";
import {
  CONTROL_FRAME_LEN,
  CONTROL_MAX_LIFETIME_SEC,
  CONTROL_ON_WIRE_LEN,
  CONTROL_PAYLOAD_LEN,
  CONTROL_PAYLOAD_OFF,
  CONTROL_PUBKEY_OFF,
  CONTROL_SIGN_DOMAIN,
  CONTROL_SIG_OFF,
  CONTROL_TYPE_BACKEND_RESOLVE,
  CONTROL_TYPE_DELIVERY,
  CONTROL_TYPE_SELF_CANCEL,
  ControlFrameType,
  MESH_PUBKEY_LEN,
  MESH_SIG_LEN,
} from "./constants.js";

/**
 * Control payload v1 — 22 bytes, big-endian, byte-identical with
 * net.guacamaya.proto.ControlPayload (Android):
 *
 *   off 0      type            0x01 delivery | 0x02 backend-resolve | 0x03 self-cancel
 *   off 1..4   targetNodeId    SHA-256(victim pubkey)[0..3]
 *   off 5..6   targetMsgId     u16 — msgId of the SOS session being addressed
 *   off 7..10  issuedAt        u32 unix seconds
 *   off 11..14 expiresAt       u32 unix seconds
 *   off 15..16 ctrlMsgId       u16 — CSPRNG per control message (dedupe key)
 *   off 17..19 reserved        must be zero (reject otherwise; no silent v2)
 *   off 20..21 crc             CRC16-CCITT over bytes 0..19
 *
 * Signature is domain-separated: Ed25519 over
 * UTF8(CONTROL_SIGN_DOMAIN) || payload22 — never over the bare payload,
 * so SOS and control signatures are not interchangeable.
 */

export interface ControlPayloadFields {
  type: ControlFrameType;
  /** SHA-256(victim pubkey)[0..3], 4 bytes. */
  targetNodeId: Uint8Array;
  targetMsgId: number;
  /** Unix seconds. */
  issuedAt: number;
  /** Unix seconds; lifetime capped per type by CONTROL_MAX_LIFETIME_SEC. */
  expiresAt: number;
  ctrlMsgId: number;
}

export interface VerifiedControlFrame extends ControlPayloadFields {
  targetNodeIdHex: string;
  issuerPublicKeyHex: string;
  /** SHA-256(issuer pubkey)[0..3] as hex — dedupe namespace with ctrlMsgId. */
  issuerNodeIdHex: string;
  signatureHex: string;
  payload: Uint8Array;
  /** Canonical 118-byte upload frame (TTL stripped). */
  frame: Uint8Array;
}

export type ControlFrameResult =
  | { ok: true; control: VerifiedControlFrame }
  | { ok: false; reason: string };

const CONTROL_TYPES: readonly ControlFrameType[] = [
  CONTROL_TYPE_DELIVERY,
  CONTROL_TYPE_BACKEND_RESOLVE,
  CONTROL_TYPE_SELF_CANCEL,
];

function isControlType(type: number): type is ControlFrameType {
  return (CONTROL_TYPES as readonly number[]).includes(type);
}

function checkU16(value: number, name: string): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffff) throw new Error(`${name} out of u16 range`);
}

function checkU32(value: number, name: string): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) throw new Error(`${name} out of u32 range`);
}

/** Encodes the 22-byte control payload, computing the trailing CRC. Throws on invalid fields. */
export function encodeControlPayload(fields: ControlPayloadFields): Uint8Array {
  if (!isControlType(fields.type)) throw new Error("unknown control type");
  if (fields.targetNodeId.length !== 4) throw new Error("targetNodeId must be 4 bytes");
  checkU16(fields.targetMsgId, "targetMsgId");
  checkU16(fields.ctrlMsgId, "ctrlMsgId");
  checkU32(fields.issuedAt, "issuedAt");
  checkU32(fields.expiresAt, "expiresAt");
  if (fields.expiresAt <= fields.issuedAt) throw new Error("expiresAt must be after issuedAt");
  if (fields.expiresAt - fields.issuedAt > CONTROL_MAX_LIFETIME_SEC[fields.type]) {
    throw new Error("lifetime exceeds per-type maximum");
  }
  const payload = new Uint8Array(CONTROL_PAYLOAD_LEN);
  const dv = new DataView(payload.buffer);
  payload[0] = fields.type;
  payload.set(fields.targetNodeId, 1);
  dv.setUint16(5, fields.targetMsgId, false);
  dv.setUint32(7, fields.issuedAt, false);
  dv.setUint32(11, fields.expiresAt, false);
  dv.setUint16(15, fields.ctrlMsgId, false);
  // bytes 17..19 stay zero (reserved)
  dv.setUint16(20, crc16Ccitt(payload, 0, 20), false);
  return payload;
}

/** The exact bytes the Ed25519 signature covers: UTF8 domain prefix + payload. */
export function controlSignBytes(payload: Uint8Array): Uint8Array {
  const domain = new TextEncoder().encode(CONTROL_SIGN_DOMAIN);
  const out = new Uint8Array(domain.length + payload.length);
  out.set(domain, 0);
  out.set(payload, domain.length);
  return out;
}

/** Builds the canonical 118-byte upload frame: payload22 + pubkey32 + sig64. */
export async function buildControlFrame(
  fields: ControlPayloadFields,
  privateKey: Uint8Array,
): Promise<Uint8Array> {
  const payload = encodeControlPayload(fields);
  const pubkey = await ed.getPublicKeyAsync(privateKey);
  const sig = await ed.signAsync(controlSignBytes(payload), privateKey);
  const frame = new Uint8Array(CONTROL_FRAME_LEN);
  frame.set(payload, CONTROL_PAYLOAD_OFF);
  frame.set(pubkey, CONTROL_PUBKEY_OFF);
  frame.set(sig, CONTROL_SIG_OFF);
  return frame;
}

export interface VerifyControlFrameOptions {
  /** Wall clock in ms (Date.now()). */
  nowMs: number;
  /**
   * Pinned backend Ed25519 public key (hex). Required to accept delivery /
   * backend-resolve frames; without it those types are rejected, never trusted.
   */
  backendPublicKeyHex?: string;
}

/**
 * Full zero-trust verification cascade, cheapest first — mirrors the Kotlin
 * FloodRouter control branch:
 *   size → type/reserved → CRC → time window → Ed25519 (domain-prefixed) →
 *   authorization (backend pin for 0x01/0x02, self-binding for 0x03).
 * Dedupe (issuerNodeId+ctrlMsgId) and replay-by-signature stay with the caller,
 * which owns the persistent stores.
 */
export async function verifyControlFrame(
  bytes: Uint8Array,
  opts: VerifyControlFrameOptions,
): Promise<ControlFrameResult> {
  let frame = bytes;
  if (frame.length === CONTROL_ON_WIRE_LEN) frame = frame.subarray(1);
  if (frame.length !== CONTROL_FRAME_LEN) {
    return { ok: false, reason: `bad control frame length ${bytes.length} (need ${CONTROL_FRAME_LEN})` };
  }

  const payload = frame.subarray(CONTROL_PAYLOAD_OFF, CONTROL_PAYLOAD_OFF + CONTROL_PAYLOAD_LEN);
  const pubkey = frame.subarray(CONTROL_PUBKEY_OFF, CONTROL_PUBKEY_OFF + MESH_PUBKEY_LEN);
  const sig = frame.subarray(CONTROL_SIG_OFF, CONTROL_SIG_OFF + MESH_SIG_LEN);
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);

  const type = payload[0]!;
  if (!isControlType(type)) return { ok: false, reason: `unknown control type 0x${type.toString(16)}` };
  if (payload[17] !== 0 || payload[18] !== 0 || payload[19] !== 0) {
    return { ok: false, reason: "reserved bytes not zero" };
  }

  if (dv.getUint16(20, false) !== crc16Ccitt(payload, 0, 20)) {
    return { ok: false, reason: "crc mismatch" };
  }

  const issuedAt = dv.getUint32(7, false);
  const expiresAt = dv.getUint32(11, false);
  const nowSec = Math.floor(opts.nowMs / 1000);
  if (expiresAt <= issuedAt) return { ok: false, reason: "expiresAt not after issuedAt" };
  if (expiresAt - issuedAt > CONTROL_MAX_LIFETIME_SEC[type]) {
    return { ok: false, reason: "lifetime exceeds per-type maximum" };
  }
  if (expiresAt < nowSec) return { ok: false, reason: "control frame expired" };

  let sigValid = false;
  try {
    sigValid = await ed.verifyAsync(sig, controlSignBytes(payload), pubkey);
  } catch {
    sigValid = false;
  }
  if (!sigValid) return { ok: false, reason: "signature invalid" };

  const targetNodeId = payload.subarray(1, 5);
  const issuerNodeId = sha256(pubkey).subarray(0, 4);
  const pubkeyHex = bytesToHex(pubkey);
  if (type === CONTROL_TYPE_SELF_CANCEL) {
    // Only the victim's own key may cancel its SOS: issuer node id must be the target.
    for (let i = 0; i < 4; i++) {
      if (issuerNodeId[i] !== targetNodeId[i]) return { ok: false, reason: "self-cancel issuer is not the target" };
    }
  } else {
    if (!opts.backendPublicKeyHex) return { ok: false, reason: "backend key not pinned" };
    if (pubkeyHex !== opts.backendPublicKeyHex.toLowerCase()) {
      return { ok: false, reason: "issuer is not the pinned backend" };
    }
  }

  return {
    ok: true,
    control: {
      type,
      targetNodeId: new Uint8Array(targetNodeId),
      targetNodeIdHex: bytesToHex(targetNodeId),
      targetMsgId: dv.getUint16(5, false),
      issuedAt,
      expiresAt,
      ctrlMsgId: dv.getUint16(15, false),
      issuerPublicKeyHex: pubkeyHex,
      issuerNodeIdHex: bytesToHex(issuerNodeId),
      signatureHex: bytesToHex(sig),
      payload: new Uint8Array(payload),
      frame: new Uint8Array(frame),
    },
  };
}
