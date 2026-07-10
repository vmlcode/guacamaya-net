import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { ChannelRecord, LocationPoint } from "./types.js";
import { canonicalJsonString } from "./canonical.js";

/**
 * Computes the SHA-256 hash byte array of a record's canonical contents.
 */
export function getRecordHashContent(record: Omit<ChannelRecord, "id" | "sig">): Uint8Array {
  // Supabase stores payload as jsonb and may return object keys in a different
  // order. Sign canonical JSON so persistence/transport cannot change the hash.
  const serializedPayload = canonicalJsonString(record.payload);
  // Keep v1 IDs byte-identical when no chain extension is present.
  const chain = record.prevHash ? `:${record.prevHash}` : "";
  const content = `${record.channel}:${record.timestamp}:${record.ttl}:${record.author}:${record.verified}${chain}:${serializedPayload}`;
  return sha256(new TextEncoder().encode(content));
}

/**
 * Generates the deterministic unique identifier (id) of a record.
 */
export function getRecordId(record: Omit<ChannelRecord, "id" | "sig">): string {
  const hashBytes = getRecordHashContent(record);
  return bytesToHex(hashBytes);
}

/**
 * Generates the deterministic unique identifier for a LocationPoint.
 * Mirror of getRecordId; same content → same id across all mules.
 */
export function getLocationId(p: Omit<LocationPoint, "id">): string {
  const content = `${p.deviceId}:${p.lat}:${p.lon}:${p.timestamp}:${p.accuracy ?? ""}`;
  return bytesToHex(sha256(new TextEncoder().encode(content)));
}

/**
 * Verifies if the signature on a record is valid using the backend's public key.
 */
export async function verifyRecordSignature(
  record: ChannelRecord,
  publicKeyHex: string
): Promise<boolean> {
  if (!record.sig) {
    return false;
  }
  try {
    const hash = getRecordHashContent(record);
    const signatureBytes = hexToBytes(record.sig);
    const publicKeyBytes = hexToBytes(publicKeyHex);
    return await ed.verifyAsync(signatureBytes, hash, publicKeyBytes);
  } catch (err) {
    return false;
  }
}
