import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { ChannelRecord } from "@guacamaya/shared";

/**
 * Server-side decoder + verifier for SOSNet BLE mesh frames uploaded by the
 * Android app acting as a data mule.
 *
 * The phone uploads each frame as Base64. The on-wire BLE service data is
 * 119 B (1 B unsigned hop TTL + 22 B payload + 32 B pubkey + 64 B signature),
 * but the client strips the mutable TTL before upload, so the expected upload
 * frame is 118 B:
 *
 *   bytes 0..21   22 B signed payload  (proto/Payload.kt, big-endian)
 *   bytes 22..53  32 B Ed25519 public key
 *   bytes 54..117 64 B Ed25519 signature over the 22 B payload
 *
 * A 119 B frame (TTL not stripped) is tolerated by dropping the leading byte.
 *
 * Verification mirrors the Kotlin FloodRouter reject cascade, cheapest first:
 *   1. CRC16-CCITT over payload[0..19]            (structural / junk reject)
 *   2. SHA-256(pubkey)[0..3] == payload.node_id   (pubkey binding; defeats key swap)
 *   3. Ed25519 verify(sig, payload, pubkey)       (the authoritative gate)
 *
 * The timestamp-skew check from the live mesh is deliberately NOT applied here:
 * a data mule may upload reports collected hours earlier, and rejecting stale
 * frames would defeat the whole point of mule sync.
 */

const PAYLOAD_LEN = 22;
const PUBKEY_LEN = 32;
const SIG_LEN = 64;
const FRAME_LEN = PAYLOAD_LEN + PUBKEY_LEN + SIG_LEN; // 118

const PAYLOAD_OFF = 0;
const PUBKEY_OFF = PAYLOAD_OFF + PAYLOAD_LEN; // 22
const SIG_OFF = PUBKEY_OFF + PUBKEY_LEN; // 54

/** SOS type codes — mirror of org.sosnet.proto.SosType. */
const SOS_TYPES = [
  "medical",
  "distress",
  "food",
  "water",
  "shelter",
  "fire",
  "violence",
  "other",
] as const;

/**
 * CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection, no final XOR).
 * Byte-for-byte mirror of org.sosnet.proto.Crc16.ccitt.
 */
function crc16(data: Uint8Array, offset = 0, length = data.length): number {
  let crc = 0xffff;
  for (let i = offset; i < offset + length; i++) {
    crc ^= (data[i]! & 0xff) << 8;
    crc &= 0xffff;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x8000) !== 0 ? (crc << 1) ^ 0x1021 : crc << 1;
      crc &= 0xffff;
    }
  }
  return crc;
}

export type FrameResult =
  | { ok: true; record: ChannelRecord }
  | { ok: false; reason: string };

/**
 * Decode and cryptographically verify a single Base64-encoded mesh frame.
 * Returns a community `ChannelRecord` (verified === false — the Ed25519 check is
 * the ingestion gate, NOT the "official/backend-signed" flag) or a rejection.
 */
export async function decodeAndVerifyFrame(frameB64: string): Promise<FrameResult> {
  let raw: Buffer;
  try {
    raw = Buffer.from(frameB64, "base64");
  } catch {
    return { ok: false, reason: "invalid base64" };
  }

  // Tolerate an un-stripped 119 B frame by dropping the leading hop-TTL byte.
  let bytes: Uint8Array = raw;
  if (bytes.length === FRAME_LEN + 1) {
    bytes = bytes.subarray(1);
  }
  if (bytes.length !== FRAME_LEN) {
    return { ok: false, reason: `bad frame length ${bytes.length} (need ${FRAME_LEN})` };
  }

  const payload = bytes.subarray(PAYLOAD_OFF, PAYLOAD_OFF + PAYLOAD_LEN);
  const pubkey = bytes.subarray(PUBKEY_OFF, PUBKEY_OFF + PUBKEY_LEN);
  const sig = bytes.subarray(SIG_OFF, SIG_OFF + SIG_LEN);
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);

  // 1. CRC16 (cheap reject before the expensive verify).
  const crcStored = dv.getUint16(20, false);
  const crcComputed = crc16(payload, 0, 20);
  if (crcStored !== crcComputed) {
    return { ok: false, reason: "crc mismatch" };
  }

  // 2. Pubkey binding: node_id (payload[12..16]) === SHA-256(pubkey)[0..4].
  const nodeId = payload.subarray(12, 16);
  const expectedNodeId = sha256(pubkey).subarray(0, 4);
  for (let i = 0; i < 4; i++) {
    if (nodeId[i] !== expectedNodeId[i]) {
      return { ok: false, reason: "node_id / pubkey mismatch" };
    }
  }

  // 3. Ed25519 signature over the full 22 B payload — mirrors Kotlin Signer.verify.
  let sigValid = false;
  try {
    sigValid = await ed.verifyAsync(sig, payload, pubkey);
  } catch {
    sigValid = false;
  }
  if (!sigValid) {
    return { ok: false, reason: "signature invalid" };
  }

  // ---- Verified. Build a community ChannelRecord. ----
  const latE7 = dv.getInt32(0, false);
  const lonE7 = dv.getInt32(4, false);
  const tsUnix = dv.getUint32(8, false); // seconds
  const flags = payload[16]!;
  const sosTypeCode = payload[17]!;
  const msgId = dv.getUint16(18, false);

  const hasHeavy = (flags & 0x01) !== 0;
  const critical = (flags & 0x02) !== 0;
  const batteryBucket = (flags >> 2) & 0x03;
  const originHopTtl = (flags >> 4) & 0x0f;

  const pubkeyHex = bytesToHex(pubkey);
  const nodeIdHex = bytesToHex(nodeId);

  // Deterministic id = SHA-256 of the signed payload, hex. Same payload → same id,
  // so re-uploads of the same frame dedupe on Supabase's `id` primary key — the
  // union+dedupe-by-id invariant the mesh gossip log uses.
  const id = bytesToHex(sha256(payload));

  const record: ChannelRecord = {
    id,
    channel: "solicito-ayuda", // community SOS distress channel
    timestamp: tsUnix * 1000, // ChannelRecord.timestamp is unix ms
    ttl: 0, // ingested into the backend; not re-flooded into the mesh
    author: `device-${pubkeyHex}`,
    verified: false, // community report — authenticated to a device, not official
    payload: {
      source: "mesh-ble",
      nodeId: nodeIdHex,
      msgId,
      tsUnix,
      lat: latE7 / 1e7,
      lon: lonE7 / 1e7,
      sosType: SOS_TYPES[sosTypeCode] ?? "other",
      sosTypeCode,
      critical,
      hasHeavy,
      batteryBucket,
      originHopTtl,
      frameB64: Buffer.from(bytes).toString("base64"),
    },
    sig: bytesToHex(sig),
  };

  return { ok: true, record };
}
