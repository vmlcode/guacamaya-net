import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { ChannelRecord, getRecordId } from "@guacamaya/shared";
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
