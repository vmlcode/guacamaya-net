import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { ChannelRecord, canonicalJsonString, getRecordId } from "@guacamaya/shared";
import { privateKey } from "./keys.js";

/**
 * Signs an unsigned record, setting verified to true, generating its deterministic hash ID,
 * and creating the Ed25519 signature using the backend's private key.
 */
export async function signRecord(
  record: Omit<ChannelRecord, "sig" | "id" | "verified">
): Promise<ChannelRecord> {
  const verifiedRecord = {
    ...record,
    // Emit the same sorted representation that is signed. This keeps clients
    // which verify the wire payload byte-for-byte compatible with jsonb.
    payload: JSON.parse(canonicalJsonString(record.payload)),
    verified: true,
  };

  const recordId = getRecordId(verifiedRecord);
  const hashBytes = hexToBytes(recordId);
  const sigBytes = await ed.signAsync(hashBytes, privateKey);
  const sigHex = bytesToHex(sigBytes);

  return {
    ...verifiedRecord,
    id: recordId,
    sig: sigHex,
  };
}
