import { FastifyInstance } from "fastify";
import {
  getResolveId,
  verifyResolveEnvelope,
  verifyWitnessSignature,
  type ChannelId,
  type ChannelRecord,
  type ResolveEnvelope,
  type ResolveReceipt,
  type ResolveWitness,
} from "@guacamaya/shared";
import { channelsRepo } from "../db/channelsRepo.js";
import { resolvesRepo } from "../db/resolvesRepo.js";
import { signRecord } from "../crypto/signer.js";
import { broadcastRecord, broadcastResolve } from "../ws/server.js";
import { requireApiKey } from "../security/auth.js";
import { securityConfig, effectiveReadKey } from "../security/config.js";
import { isValidResolveEnvelope } from "../security/validation.js";
import { perWitnessResolveLimit } from "../security/rateLimit.js";
import { storeEvidence, verifyUploadToken } from "./evidence.js";

/**
 * Resolve flow — finder co-signed disarm of an active SOS.
 *
 * Two endpoints:
 *   POST /resolve/evidence — uploads one image, returns imageHash + uploadToken.
 *   POST /resolve          — submits the witness envelope; backend re-verifies
 *                            every signature and runs the anti-troll cascade.
 *
 * Anti-troll gates (cheapest first):
 *   1. envelope shape           → 400
 *   2. target SOS exists         → target_unknown
 *   3. target recency ≤ 72 h     → target_stale
 *   4. submitter rate-limit      → rate_limited
 *   5. witness ≠ originator       → skipped silently
 *   6. one-witness-per-target     → skipped silently (dup)
 *   7. geo ≤ radius per witness   → geo_radius_exceeded (witness skipped)
 *   8. signature valid per witness → invalid skipped
 *   9. uploadToken valid per witness → invalid skipped
 *  10. ≥ M valid accepted         → if not, accepted:false quorum not reached
 *
 * Duplicate witnesses (already signed this target) are silently skipped rather
 * than rejected, so co-located finders can submit overlapping envelopes without
 * blocking each other. If no new witness is accepted, returns duplicate_witness.
 */

const EARTH_RADIUS_KM = 6371;

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
}

interface TargetSos {
  id: string;
  author: string;
  timestamp: number;
  lat: number;
  lon: number;
}

function extractTargetSos(rec: ChannelRecord): TargetSos | null {
  const payload = rec.payload as { lat?: number; lon?: number };
  if (typeof payload?.lat !== "number" || typeof payload?.lon !== "number") return null;
  return {
    id: rec.id,
    author: rec.author,
    timestamp: rec.timestamp,
    lat: payload.lat,
    lon: payload.lon,
  };
}

async function emitResueltoRecord(
  target: TargetSos,
  receipt: ResolveReceipt,
  eventType: "pending" | "cleared" | "disputed"
): Promise<void> {
  const unsigned = {
    channel: "resuelto" as ChannelId,
    timestamp: Date.now(),
    ttl: 0,
    author: "backend",
    payload: {
      source: "resolve-flow",
      event: eventType,
      targetSosId: target.id,
      targetSosAuthor: target.author,
      receiptId: receipt.id,
      quorumNeeded: receipt.quorumNeeded,
      quorumSeen: receipt.quorumSeen,
      witnessCount: receipt.witnessDeviceIds.length,
    },
  };
  try {
    const signed = await signRecord(unsigned);
    await channelsRepo.addRecord(signed);
    broadcastRecord(signed);
  } catch (err) {
    console.error("[resolve] failed to emit resuelto record", err);
  }
}

export async function resolveRoutes(fastify: FastifyInstance) {
  // Accept raw image bytes for /resolve/evidence. Default Fastify parser only
  // handles application/json.
  fastify.addContentTypeParser(
    "application/octet-stream",
    { parseAs: "buffer" },
    (_req, body, done) => done(null, body),
  );

  fastify.post<{ Body: Buffer }>(
    "/resolve/evidence",
    {
      config: { rateLimit: securityConfig.ingestRateLimit },
      preHandler: securityConfig.resolve.evidenceRequireAuth
        ? requireApiKey(effectiveReadKey(), "resolve evidence")
        : undefined,
    },
    async (request, reply) => {
      const body = request.body;
      if (!body || body.length === 0) {
        return reply.code(400).send({ error: "Empty body" });
      }
      if (body.length > securityConfig.resolve.maxImageBytes) {
        return reply.code(413).send({ error: "Image too large" });
      }
      try {
        const stored = await storeEvidence(new Uint8Array(body));
        return {
          imageHash: stored.imageHash,
          storageKey: stored.storageKey,
          uploadToken: stored.uploadToken,
          expiresInMs: securityConfig.resolve.evidenceTtlMs,
        };
      } catch (err) {
        request.log.error(err, "evidence storage failed");
        return reply.code(500).send({ error: "Evidence storage failed" });
      }
    }
  );

  fastify.post<{ Body: ResolveEnvelope }>(
    "/resolve",
    {
      config: { rateLimit: securityConfig.ingestRateLimit },
      preHandler: perWitnessResolveLimit(),
    },
    async (request, reply) => {
      const envelope = request.body;
      if (!isValidResolveEnvelope(envelope)) {
        return reply.code(400).send({
          accepted: false,
          status: "rejected",
          reason: "invalid_envelope",
        });
      }

      const submitterDeviceId = envelope.witnesses[0]!.deviceId;
      (request as { witnessDeviceId?: string }).witnessDeviceId = submitterDeviceId;

      // Gate 2: target SOS must exist.
      const targetRec = await channelsRepo.getById(envelope.targetSosId);
      if (!targetRec) {
        return reply.code(404).send({
          accepted: false,
          status: "rejected",
          targetSosId: envelope.targetSosId,
          reason: "target_unknown",
        });
      }
      const target = extractTargetSos(targetRec);
      if (!target) {
        return reply.code(404).send({
          accepted: false,
          status: "rejected",
          targetSosId: envelope.targetSosId,
          reason: "target_no_geo",
        });
      }

      // Gate 3: target recency.
      const ageMs = Date.now() - target.timestamp;
      if (ageMs > securityConfig.resolve.targetMaxAgeH * 3600_000) {
        return reply.code(410).send({
          accepted: false,
          status: "rejected",
          targetSosId: envelope.targetSosId,
          reason: "target_stale",
        });
      }

      // Cross-check envelope's targetSosAuthor against the record's actual author.
      if (envelope.targetSosAuthor !== target.author) {
        return reply.code(400).send({
          accepted: false,
          status: "rejected",
          targetSosId: envelope.targetSosId,
          reason: "target_author_mismatch",
        });
      }

      // Gates 5–9: per-witness processing. Skip dups and originator silently;
      // skip witnesses that fail geo/sig/token. Count only newly-accepted.
      const newAccepted: ResolveWitness[] = [];
      const reasons: string[] = [];
      for (const w of envelope.witnesses) {
        if (w.deviceId === target.author) {
          reasons.push("witness_is_originator");
          continue;
        }
        const alreadyWitnessed = await resolvesRepo.hasWitness(envelope.targetSosId, w.deviceId);
        if (alreadyWitnessed) {
          reasons.push("duplicate_witness");
          continue;
        }
        const distanceKm = haversineKm(w.lat, w.lon, target.lat, target.lon);
        if (distanceKm > securityConfig.resolve.geoRadiusKm) {
          reasons.push(`geo_radius_exceeded:${distanceKm.toFixed(1)}km`);
          continue;
        }
        const sigOk = await verifyWitnessSignature(envelope, w);
        if (!sigOk) {
          reasons.push("signature_invalid");
          continue;
        }
        if (w.uploadToken && !verifyUploadToken(w.uploadToken, w.imageHash)) {
          reasons.push("upload_token_invalid");
          continue;
        }
        newAccepted.push(w);
      }

      if (newAccepted.length === 0) {
        return reply.code(200).send({
          accepted: false,
          status: "rejected",
          targetSosId: envelope.targetSosId,
          quorumNeeded: securityConfig.resolve.quorumRequired,
          quorumSeen: await resolvesRepo.getWitnesses(envelope.targetSosId).then((w) => w.length),
          reason: reasons[0] ?? "no_new_witnesses",
        });
      }

      // Persist each new witness.
      for (const w of newAccepted) {
        await resolvesRepo.addWitness(envelope.targetSosId, w);
      }

      const allWitnesses = await resolvesRepo.getWitnesses(envelope.targetSosId);
      const quorumSeen = allWitnesses.length;
      const quorumNeeded = securityConfig.resolve.quorumRequired;
      const witnessDeviceIds = allWitnesses.map((w) => w.deviceId);

      // Compose the canonical receipt with the cumulative witness set.
      const canonicalEnvelope: ResolveEnvelope = {
        ...envelope,
        witnesses: allWitnesses,
      };
      const receiptId = getResolveId(canonicalEnvelope);
      const now = Date.now();
      const previousReceipt = await resolvesRepo.getReceiptByTarget(envelope.targetSosId);
      const previouslyPending =
        previousReceipt?.status === "pending" ? previousReceipt.cooldownEndsAt : undefined;
      const cooldownEndsAt =
        previouslyPending ?? (quorumSeen >= quorumNeeded
          ? now + securityConfig.resolve.cooldownMin * 60_000
          : undefined);

      const status: ResolveReceipt["status"] =
        quorumSeen >= quorumNeeded ? "pending" : "pending"; // accumulating OR in cooldown; both "pending"

      const receipt: ResolveReceipt = {
        id: receiptId,
        targetSosId: envelope.targetSosId,
        targetSosAuthor: envelope.targetSosAuthor,
        status,
        quorumNeeded,
        quorumSeen,
        witnessDeviceIds,
        createdAt: previousReceipt?.createdAt ?? now,
        cooldownEndsAt,
      };
      await resolvesRepo.upsertReceipt(receipt);

      // On first quorum crossing, kick the cooldown + broadcast pending-clear.
      const justReachedQuorum = quorumSeen >= quorumNeeded && !previouslyPending;
      if (justReachedQuorum) {
        broadcastResolve(receipt);
        await emitResueltoRecord(target, receipt, "pending");
      } else {
        // Accumulating or repeat submission — broadcast updated receipt.
        broadcastResolve(receipt);
      }

      return {
        accepted: true,
        status: receipt.status,
        targetSosId: receipt.targetSosId,
        quorumNeeded,
        quorumSeen,
        receiptId: receipt.id,
        cooldownEndsAt: receipt.cooldownEndsAt,
        reasons,
      };
    }
  );
}
