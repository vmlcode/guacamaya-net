import { ChannelRecord } from "./types.js";
import { verifyRecordSignature } from "./crypto.js";

/**
 * Merges two logs (local and incoming), deduplicates them by record ID,
 * verifies signatures of official records (if publicKeyHex is provided),
 * and returns the merged list sorted by timestamp ascending.
 */
export async function mergeLogs(
  localLog: ChannelRecord[],
  incomingLog: ChannelRecord[],
  publicKeyHex?: string
): Promise<ChannelRecord[]> {
  const mergedMap = new Map<string, ChannelRecord>();

  // 1. Populate map with local records
  for (const record of localLog) {
    mergedMap.set(record.id, record);
  }

  // 2. Process incoming records
  for (const record of incomingLog) {
    if (mergedMap.has(record.id)) {
      continue;
    }

    // Strict signature check for verified official messages
    if (record.verified && publicKeyHex) {
      const isValid = await verifyRecordSignature(record, publicKeyHex);
      if (!isValid) {
        // Skip invalid official messages to prevent spoofing
        continue;
      }
    }

    mergedMap.set(record.id, record);
  }

  // 3. Sort by timestamp ascending
  return Array.from(mergedMap.values()).sort((a, b) => a.timestamp - b.timestamp);
}
