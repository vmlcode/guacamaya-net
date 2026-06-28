import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import {
  ChannelRecord,
  LocationPoint,
  getLocationId,
  crc16Ccitt,
  MESH_FRAME_LEN,
  MESH_PAYLOAD_LEN,
  MESH_PUBKEY_LEN,
  MESH_SIG_LEN,
  MESH_PAYLOAD_OFF,
  MESH_PUBKEY_OFF,
  MESH_SIG_OFF,
  MESH_SOS_TYPES,
} from "@guacamaya/shared";

/**
 * Server-side decoder + verifier for Guacamaya BLE mesh frames uploaded by the
 * Android app acting as a data mule.
 *
 * Verification mirrors the Kotlin FloodRouter reject cascade, cheapest first:
 *   1. CRC16-CCITT over payload[0..19]
 *   2. SHA-256(pubkey)[0..3] == payload.node_id
 *   3. Ed25519 verify(sig, payload, pubkey)
 *
 * Timestamp skew is intentionally omitted — a data mule may upload stale reports.
 */

export type FrameResult =
  | { ok: true; record: ChannelRecord; location: LocationPoint | null }
  | { ok: false; reason: string };

export async function decodeAndVerifyFrame(frameB64: string): Promise<FrameResult> {
  let raw: Buffer;
  try {
    raw = Buffer.from(frameB64, "base64");
  } catch {
    return { ok: false, reason: "invalid base64" };
  }

  let bytes: Uint8Array = raw;
  if (bytes.length === MESH_FRAME_LEN + 1) {
    bytes = bytes.subarray(1);
  }
  if (bytes.length !== MESH_FRAME_LEN) {
    return { ok: false, reason: `bad frame length ${bytes.length} (need ${MESH_FRAME_LEN})` };
  }

  const payload = bytes.subarray(MESH_PAYLOAD_OFF, MESH_PAYLOAD_OFF + MESH_PAYLOAD_LEN);
  const pubkey = bytes.subarray(MESH_PUBKEY_OFF, MESH_PUBKEY_OFF + MESH_PUBKEY_LEN);
  const sig = bytes.subarray(MESH_SIG_OFF, MESH_SIG_OFF + MESH_SIG_LEN);
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);

  const crcStored = dv.getUint16(20, false);
  if (crcStored !== crc16Ccitt(payload, 0, 20)) {
    return { ok: false, reason: "crc mismatch" };
  }

  const nodeId = payload.subarray(12, 16);
  const expectedNodeId = sha256(pubkey).subarray(0, 4);
  for (let i = 0; i < 4; i++) {
    if (nodeId[i] !== expectedNodeId[i]) {
      return { ok: false, reason: "node_id / pubkey mismatch" };
    }
  }

  let sigValid = false;
  try {
    sigValid = await ed.verifyAsync(sig, payload, pubkey);
  } catch {
    sigValid = false;
  }
  if (!sigValid) {
    return { ok: false, reason: "signature invalid" };
  }

  const latE7 = dv.getInt32(0, false);
  const lonE7 = dv.getInt32(4, false);
  const tsUnix = dv.getUint32(8, false);
  const flags = payload[16]!;
  const sosTypeCode = payload[17]!;
  const msgId = dv.getUint16(18, false);

  const hasHeavy = (flags & 0x01) !== 0;
  const critical = (flags & 0x02) !== 0;
  const batteryBucket = (flags >> 2) & 0x03;
  const originHopTtl = (flags >> 4) & 0x0f;

  const pubkeyHex = bytesToHex(pubkey);
  const nodeIdHex = bytesToHex(nodeId);
  const lat = latE7 / 1e7;
  const lon = lonE7 / 1e7;

  const id = bytesToHex(sha256(payload));

  const record: ChannelRecord = {
    id,
    channel: "solicito-ayuda",
    timestamp: tsUnix * 1000,
    ttl: 0,
    author: `device-${pubkeyHex}`,
    verified: false,
    payload: {
      source: "mesh-ble",
      nodeId: nodeIdHex,
      msgId,
      tsUnix,
      lat,
      lon,
      sosType: MESH_SOS_TYPES[sosTypeCode] ?? "other",
      sosTypeCode,
      critical,
      hasHeavy,
      batteryBucket,
      originHopTtl,
      frameB64: Buffer.from(bytes).toString("base64"),
    },
    sig: bytesToHex(sig),
  };

  let location: LocationPoint | null = null;
  const hasFix =
    Number.isFinite(lat) &&
    Number.isFinite(lon) &&
    lat >= -90 &&
    lat <= 90 &&
    lon >= -180 &&
    lon <= 180 &&
    !(lat === 0 && lon === 0);
  if (hasFix) {
    const point = {
      deviceId: `device-${pubkeyHex}`,
      lat,
      lon,
      timestamp: tsUnix * 1000,
    };
    location = { id: getLocationId(point), ...point };
  }

  return { ok: true, record, location };
}
