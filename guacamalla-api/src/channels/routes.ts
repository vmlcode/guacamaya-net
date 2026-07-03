import { FastifyInstance } from "fastify";
import { ChannelId, ChannelRecord, LocationPoint } from "@guacamaya/shared";
import { channelsRepo } from "../db/channelsRepo.js";
import { locationsRepo } from "../db/locationsRepo.js";
import { resolvesRepo } from "../db/resolvesRepo.js";
import { signRecord } from "../crypto/signer.js";
import { broadcastRecord, broadcastLocation, broadcastResolve } from "../ws/server.js";
import { publicKeyHex } from "../crypto/keys.js";
import { decodeAndVerifyFrame } from "../mesh/frame.js";
import { sanitizeRecordsForPublic } from "./sanitize.js";
import { requireApiKey } from "../security/auth.js";
import { securityConfig } from "../security/config.js";
import {
  isValidChannelId,
  isValidFrameB64,
  validateIngestBatch,
  validateOfficialPayload,
  parseSinceParam,
} from "../security/validation.js";

const OFFICIAL_CHANNELS = new Set(["alertas", "refugios", "ayuda-medica"]);

export async function channelRoutes(fastify: FastifyInstance) {
  fastify.get("/pubkey", async () => ({ publicKey: publicKeyHex }));

  fastify.get("/channels", async () => [
    { id: "alertas", name: "Alertas Oficiales", verifiedOnly: true },
    { id: "refugios", name: "Refugios y Recursos", verifiedOnly: true },
    { id: "ayuda-medica", name: "Ayuda Médica", verifiedOnly: true },
    { id: "estoy-bien", name: "Estoy Bien (Comunidad)", verifiedOnly: false },
    { id: "solicito-ayuda", name: "Solicito Ayuda (Comunidad)", verifiedOnly: false },
  ]);

  fastify.get<{ Params: { id: string }; Querystring: { since?: string } }>(
    "/channels/:id/records",
    async (request, reply) => {
      const channelId = request.params.id;
      if (!isValidChannelId(channelId)) {
        return reply.code(404).send({ error: "Unknown channel" });
      }
      const since = parseSinceParam(request.query.since);
      const records = await channelsRepo.getRecords(channelId as ChannelId, since);
      return sanitizeRecordsForPublic(records);
    },
  );

  fastify.post<{ Params: { id: string }; Body: { payload?: unknown } }>(
    "/channels/:id/records",
    {
      preHandler: requireApiKey(securityConfig.adminApiKey, "official records"),
      config: { rateLimit: securityConfig.officialWriteRateLimit },
    },
    async (request, reply) => {
      const channelId = request.params.id;
      if (!isValidChannelId(channelId) || !OFFICIAL_CHANNELS.has(channelId)) {
        return reply.code(403).send({ error: "Channel not allowed for official records" });
      }

      const { payload } = request.body ?? {};
      if (!validateOfficialPayload(payload)) {
        return reply.code(400).send({ error: "Invalid or missing payload" });
      }

      const unsigned = {
        channel: channelId as ChannelId,
        timestamp: Date.now(),
        ttl: 3,
        author: "backend",
        payload,
      };

      try {
        const signed = await signRecord(unsigned);
        await channelsRepo.addRecord(signed);
        broadcastRecord(signed);
        return signed;
      } catch (err) {
        request.log.error(err, "Failed to sign official record");
        return reply.code(500).send({ error: "Failed to sign official record" });
      }
    },
  );

  fastify.post<{ Body: { frames?: unknown } }>(
    "/ingest",
    { config: { rateLimit: securityConfig.ingestRateLimit } },
    async (request, reply) => {
      const { frames } = request.body ?? {};

      if (!validateIngestBatch(frames)) {
        const tooMany = Array.isArray(frames) && frames.length > securityConfig.maxIngestBatch;
        return reply.code(400).send({
          error: tooMany
            ? `Batch exceeds max ${securityConfig.maxIngestBatch} frames`
            : "Missing or invalid 'frames' array",
        });
      }

      let ingested = 0;
      let duplicate = 0;
      let rejected = 0;
      const reasons: Record<string, number> = {};
      const verifiedLocations: LocationPoint[] = [];

      for (const frame of frames) {
        if (typeof frame !== "string" || !isValidFrameB64(frame)) {
          rejected++;
          reasons["invalid frame string"] = (reasons["invalid frame string"] ?? 0) + 1;
          continue;
        }

        const result = await decodeAndVerifyFrame(frame);
        if (!result.ok) {
          rejected++;
          reasons[result.reason] = (reasons[result.reason] ?? 0) + 1;
          continue;
        }

        const added = await channelsRepo.addRecord(result.record);
        if (added) {
          ingested++;
          broadcastRecord(result.record);
          await checkOriginatorVeto(result.record);
        } else {
          duplicate++;
        }

        if (result.location) {
          verifiedLocations.push(result.location);
        }
      }

      let locationsIngested = 0;
      if (verifiedLocations.length > 0) {
        locationsIngested = await locationsRepo.addPoints(verifiedLocations);
        for (const point of verifiedLocations) {
          broadcastLocation(point);
        }
      }

      if (rejected > 0) {
        request.log.warn({ rejected, reasons }, "rejected unverifiable mesh frames");
      }

      return { success: true, ingested, duplicate, rejected, locationsIngested, reasons };
    },
  );
}

/**
 * Originator veto — if a verified SOS frame arrives from a device that has a
 * pending-clear on a *different* target, that pending clear is auto-disputed.
 * The originator's device is presumed alive and re-broadcasting, which means
 * the resolve was false (or premature). Evidence is preserved for review.
 *
 * Only fires on freshly-ingested frames (`added === true`) — re-uploads of the
 * same frame id are dedupe-no-ops and must not flip a clear status.
 */
async function checkOriginatorVeto(record: ChannelRecord): Promise<void> {
  if (!record.author.startsWith("device-")) return;
  const pendingTarget = await resolvesRepo.findPendingClearForAuthor(record.author, record.id);
  if (!pendingTarget) return;
  await resolvesRepo.markDisputed(pendingTarget, "originator_refire");
  const disputed = await resolvesRepo.getReceiptByTarget(pendingTarget);
  if (disputed) {
    broadcastResolve({ ...disputed, status: "disputed", disputedReason: "originator_refire" });
  }
}
