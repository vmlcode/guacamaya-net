import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { ChannelRecord } from "./types.js";

/**
 * Computes the SHA-256 hash byte array of a record's canonical contents.
 */
export function getRecordHashContent(record: Omit<ChannelRecord, "id" | "sig">): Uint8Array {
  const serializedPayload = JSON.stringify(record.payload);
  const content = `${record.channel}:${record.timestamp}:${record.ttl}:${record.author}:${record.verified}:${serializedPayload}`;
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
