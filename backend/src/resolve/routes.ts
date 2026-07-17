import { FastifyInstance } from "fastify";
import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import {
  getResolveId,
  verifyResolveEnvelope,
  verifyWitnessSignature,
  hashCanonical,
  deriveSosId,
  computeEventId,
  computePayloadHash,
  canonicalEventBytes,
  type ChannelId,
  type ChannelRecord,
  type LedgerEvent,
  type ResolveEnvelope,
  type ResolvePayload,
  type ResolveQuorumProof,
  type ResolveReceipt,
  type ResolveWitness,
  type UnsignedLedgerEvent,
} from "@guacamaya/shared";
import { channelsRepo } from "../db/channelsRepo.js";
import { resolvesRepo } from "../db/resolvesRepo.js";
import { ledgerRepo } from "../ledger/ledgerRepo.js";
import { signRecord } from "../crypto/signer.js";
import { broadcastRecord, broadcastResolve } from "../ws/server.js";
import { privateKey, publicKeyBytes } from "../crypto/keys.js";
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

/**
 * Derive SOS_RESOLVED ledger event from a freshly-crossed quorum and upsert it.
 * The witnesses (cumulative set) carry their own Ed25519 signatures; the backend
 * re-canonicalizes them into witnessEnvelopeHash, signs canonicalEventBytes with
 * the backend key, and persists. Idempotent by eventId — re-crossing the same
 * quorum with the same witness set produces the same eventId and dedupes.
 *
 * The ledger event is *additive* — it cannot veto a CANCELLED with higher rank.
 * reconcileState picks the winner per compareAuthority.
 */
async function emitResolveQuorumLedgerEvent(
  targetRec: ChannelRecord,
  receipt: ResolveReceipt,
  witnesses: ResolveWitness[],
): Promise<void> {
  if (!targetRec.author.startsWith("device-")) return;
  const pubkeyHex = targetRec.author.slice("device-".length);
  const messageId = (targetRec.payload as { msgId?: unknown }).msgId;
  if (typeof messageId !== "number" || !Number.isFinite(messageId)) return;

  // The sosId belongs to the originator (author of the SOS frame), not to the
  // backend. The backend *signs* the event via eventSignature, but issuerPublicKey
  // here is the backend key — verifyLedgerEvent checks the backend signature, and
  // the proof.witnessEnvelopeHash binds the witnesses. sosId is recomputed from
  // the originator's pubkey so this RESOLVED event chains under the same sosId
  // as the SOS_CREATED.
  const originPubkey = hexToBytes(pubkeyHex);
  const sosId = deriveSosId(originPubkey, messageId);
  const issuerPublicKey = publicKeyBytes;

  const previousEvents = await ledgerRepo.getEventsBySosId(sosId);
  const created = previousEvents.find((s) => s.event.type === "SOS_CREATED");
  const previousSosEventHash = created?.event.eventId ?? null;

  const now = Date.now();
  const payload: ResolvePayload = {
    payloadVersion: 1,
    resolvedAt: now,
    quorumReachedAt: now,
    backendIssuedAt: now,
    witnesses,
  };
  const payloadHash = computePayloadHash(payload);
  const proof: ResolveQuorumProof = {
    proofType: "resolve-quorum",
    proofVersion: 1,
    resolveReceiptId: receipt.id,
    witnessEnvelopeHash: bytesToHex(hashCanonical(witnesses)),
    quorumCount: witnesses.length,
  };
  const unsigned: UnsignedLedgerEvent = {
    version: 1,
    sosId,
    type: "SOS_RESOLVED",
    previousSosEventHash,
    issuedAt: now,
    issuerPublicKey,
    payload,
    payloadHash,
    payloadVersion: 1,
    proof,
  };
  const eventId = computeEventId(unsigned);
  const eventSignature = await ed.signAsync(canonicalEventBytes(unsigned), privateKey);
  const event: LedgerEvent = { ...unsigned, eventId, eventSignature };
  try {
    await ledgerRepo.upsertEvent(event);
  } catch (err) {
    console.error("[ledger] SOS_RESOLVED quorum upsert failed", err);
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

  // The quorum endpoint emits a terminal SOS_RESOLVED event and therefore does
  // not implement certified, visual-only soft-resolve semantics. Keep it out of
  // the route table unless a developer explicitly opts into legacy testing.
  if (!securityConfig.resolve.legacyEndpointEnabled) return;

  fastify.post<{ Body: ResolveEnvelope }>(
    "/resolve",
    {
      config: { rateLimit: securityConfig.ingestRateLimit },
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

      const now = Date.now();
      if (
        envelope.submittedAt < now - securityConfig.resolve.witnessMaxAgeMs ||
        envelope.submittedAt > now + securityConfig.resolve.clockSkewMs
      ) {
        return reply.code(400).send({
          accepted: false,
          status: "rejected",
          reason: "submitted_at_out_of_window",
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

      // Authenticate the identity before consuming its device-scoped tokens.
      // Otherwise an attacker can spoof a victim's deviceId and exhaust that
      // victim's bucket with invalid envelopes.
      const submitterSignatureValid = await verifyWitnessSignature(
        envelope,
        envelope.witnesses[0]!,
      );
      if (!submitterSignatureValid) {
        return reply.code(400).send({
          accepted: false,
          status: "rejected",
          targetSosId: envelope.targetSosId,
          reason: "signature_invalid",
        });
      }
      const rateLimitResponse = await perWitnessResolveLimit()(request, reply);
      if (rateLimitResponse) return rateLimitResponse;

      // Gates 5–9: per-witness processing. Skip dups and originator silently;
      // skip witnesses that fail geo/sig/token. Count only newly-accepted.
      const newAccepted: ResolveWitness[] = [];
      const reasons: string[] = [];
      for (const w of envelope.witnesses) {
        if (w.deviceId === target.author) {
          reasons.push("witness_is_originator");
          continue;
        }
        if (
          w.ts < now - securityConfig.resolve.witnessMaxAgeMs ||
          w.ts > now + securityConfig.resolve.clockSkewMs ||
          Math.abs(w.ts - envelope.submittedAt) > securityConfig.resolve.witnessMaxAgeMs
        ) {
          reasons.push("witness_timestamp_out_of_window");
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
      const provisionalId = getResolveId(envelope);
      for (const w of newAccepted) {
        await resolvesRepo.addWitness(envelope.targetSosId, w, provisionalId);
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

      // On first quorum crossing, kick the cooldown and emit the ledger verdict.
      // The ledger SOS_RESOLVED event is the single source of truth for all visible
      // effects (terminalControlTrusts → stop-victim-transmission on the mule path).
      // The legacy broadcast (receipt WS push + "resuelto" record) is gated off by
      // default and forced off in production — it only duplicated the ledger verdict.
      const justReachedQuorum = quorumSeen >= quorumNeeded && !previouslyPending;
      if (justReachedQuorum) {
        if (securityConfig.resolve.legacyBroadcast) {
          broadcastResolve(receipt);
          await emitResueltoRecord(target, receipt, "pending");
        }
        await emitResolveQuorumLedgerEvent(targetRec, receipt, allWitnesses);
      } else if (securityConfig.resolve.legacyBroadcast) {
        // Accumulating or repeat submission — broadcast updated receipt (legacy).
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
