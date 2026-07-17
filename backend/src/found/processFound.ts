import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import {
  type ChannelRecord,
  haversineMeters,
  verifyFoundFrame,
} from "@guacamaya/shared";
import { isValidFrameB64 } from "../security/validation.js";

/** Community channel that holds "Encontrado" (found) reports. */
export const FOUND_CHANNEL = "encontrado";

/** The target SOS's last reported point, resolved by the caller's data layer. */
export interface FoundTargetSos {
  recordId: string;
  lat: number;
  lon: number;
}

export interface FoundDeps {
  /** Meters; at/under → near/likely_found, over → far/possible_false_positive. */
  proximityNearMeters: number;
  /** Latest SOS record for (targetNodeId, targetMsgId), or null when none with a GPS fix exists. */
  findTargetSos(targetNodeIdHex: string, targetMsgId: number): Promise<FoundTargetSos | null>;
  /** Persist; returns false when the id already exists (dedupe). */
  addRecord(record: ChannelRecord): Promise<boolean>;
  /** WS fan-out for freshly persisted records only. */
  broadcast(record: ChannelRecord): void;
}

export interface FoundResultEntry {
  /** ChannelRecord id of the found report (SHA-256(payload22) hex). */
  id: string;
  targetRecordId: string;
  distanceMeters: number;
  proximity: "near" | "far";
  verdict: "likely_found" | "possible_false_positive";
}

export interface ProcessFoundSummary {
  accepted: number;
  duplicate: number;
  rejected: number;
  reasons: Record<string, number>;
  results: FoundResultEntry[];
}

/**
 * Zero-trust "Encontrado" ingestion: every frame is re-verified (CRC + Ed25519
 * over the found domain) before anything is persisted, the target SOS must
 * already exist server-side, and the proximity verdict is computed from the
 * finder's *signed* GPS position against the SOS's last reported point — the
 * client never supplies a verdict or a record id.
 */
export async function processFoundFrames(
  frames: string[],
  deps: FoundDeps,
): Promise<ProcessFoundSummary> {
  const summary: ProcessFoundSummary = {
    accepted: 0,
    duplicate: 0,
    rejected: 0,
    reasons: {},
    results: [],
  };
  const reject = (reason: string) => {
    summary.rejected++;
    summary.reasons[reason] = (summary.reasons[reason] ?? 0) + 1;
  };

  for (const b64 of frames) {
    if (typeof b64 !== "string" || !isValidFrameB64(b64)) {
      reject("invalid frame string");
      continue;
    }
    const bytes = Uint8Array.from(Buffer.from(b64, "base64"));

    const res = await verifyFoundFrame(bytes);
    if (!res.ok) {
      reject(res.reason);
      continue;
    }
    const f = res.found;

    const target = await deps.findTargetSos(f.targetNodeIdHex, f.targetMsgId);
    if (!target) {
      reject("unknown target sos");
      continue;
    }

    const distanceMeters = Math.round(haversineMeters(f.lat, f.lon, target.lat, target.lon));
    const near = distanceMeters <= deps.proximityNearMeters;
    const proximity = near ? ("near" as const) : ("far" as const);
    const verdict = near ? ("likely_found" as const) : ("possible_false_positive" as const);

    const record: ChannelRecord = {
      id: bytesToHex(sha256(f.payload)),
      channel: FOUND_CHANNEL,
      timestamp: f.tsUnix * 1000,
      ttl: 0,
      author: `device-${f.finderPublicKeyHex}`,
      verified: false,
      payload: {
        source: "mesh-found",
        finderNodeId: f.finderNodeIdHex,
        foundMsgId: f.foundMsgId,
        tsUnix: f.tsUnix,
        lat: f.lat,
        lon: f.lon,
        targetNodeId: f.targetNodeIdHex,
        targetMsgId: f.targetMsgId,
        targetRecordId: target.recordId,
        distanceMeters,
        proximity,
        verdict,
        frameB64: Buffer.from(f.frame).toString("base64"),
      },
      sig: f.signatureHex,
    };

    const added = await deps.addRecord(record);
    if (added) {
      summary.accepted++;
      deps.broadcast(record);
    } else {
      summary.duplicate++;
    }
    // The verdict is returned even on duplicate — the mule uploading the frame
    // is usually the finder's own phone, and it needs the answer both times.
    summary.results.push({
      id: record.id,
      targetRecordId: target.recordId,
      distanceMeters,
      proximity,
      verdict,
    });
  }

  return summary;
}
