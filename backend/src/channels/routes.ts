import { FastifyInstance } from "fastify";
import {
  ChannelId,
  ChannelRecord,
  LocationPoint,
  issueRescuerCertificate,
  issueCertificateRevocationList,
  signControlReceipt,
  type ControlReceipt,
  type LedgerEvent,
  type UnsignedLedgerEvent,
  type SosCreatedPayload,
  type ChannelRecordProof,
  SOS_CHANNEL,
  deriveSosId,
  computeEventId,
  computePayloadHash,
  MESH_PAYLOAD_OFF,
  MESH_PAYLOAD_LEN,
  MESH_PUBKEY_OFF,
  MESH_PUBKEY_LEN,
  MESH_SIG_OFF,
  MESH_SIG_LEN,
} from "@guacamaya/shared";
import { hexToBytes } from "@noble/hashes/utils.js";
import { ledgerRepo } from "../ledger/ledgerRepo.js";
import { channelsRepo } from "../db/channelsRepo.js";
import { locationsRepo } from "../db/locationsRepo.js";
import { resolvesRepo } from "../db/resolvesRepo.js";
import { signRecord } from "../crypto/signer.js";
import { broadcastRecord, broadcastLocation, broadcastResolve } from "../ws/server.js";
import { privateKey, publicKeyHex } from "../crypto/keys.js";
import { decodeAndVerifyFrame } from "../mesh/frame.js";
import { processControlFrames, mintControlFrames, type SosRef } from "../control/ingestControl.js";
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

  /** Issues a short-lived NAN-only rescuer certificate. Never expose the backend key. */
  fastify.post<{ Body: { rescuerPublicKey?: string; expiresAt?: number } }>(
    "/operator/rescuer-certificates",
    { preHandler: requireApiKey(securityConfig.adminApiKey, "rescuer certificates") },
    async (request, reply) => {
      const { rescuerPublicKey, expiresAt } = request.body ?? {};
      if (typeof rescuerPublicKey !== "string" || !/^[0-9a-f]{64}$/.test(rescuerPublicKey) || typeof expiresAt !== "number" || !Number.isSafeInteger(expiresAt)) {
        return reply.code(400).send({ error: "rescuerPublicKey (Ed25519 hex) and expiresAt are required" });
      }
      try { return await issueRescuerCertificate(rescuerPublicKey, expiresAt, privateKey); }
      catch (err) { return reply.code(400).send({ error: err instanceof Error ? err.message : "invalid certificate" }); }
    },
  );

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

  fastify.post<{ Body: { frames?: unknown; controlFrames?: unknown; rescuerPublicKeys?: unknown } }>(
    "/ingest",
    { config: { rateLimit: securityConfig.ingestRateLimit } },
    async (request, reply) => {
      const { frames, controlFrames } = request.body ?? {};

      // A mule may carry SOS frames, control frames, or both. At least one batch
      // must be present and each is independently capped.
      const framesValid = validateIngestBatch(frames);
      const controlValid = validateIngestBatch(controlFrames);
      if (!framesValid && !controlValid) {
        const tooMany =
          (Array.isArray(frames) && frames.length > securityConfig.maxIngestBatch) ||
          (Array.isArray(controlFrames) && controlFrames.length > securityConfig.maxIngestBatch);
        return reply.code(400).send({
          error: tooMany
            ? `Batch exceeds max ${securityConfig.maxIngestBatch} frames`
            : "Missing or invalid 'frames'/'controlFrames' array",
        });
      }

      const frameList: string[] = framesValid ? (frames as string[]) : [];
      const controlList: string[] = controlValid ? (controlFrames as string[]) : [];

      let ingested = 0;
      let duplicate = 0;
      let rejected = 0;
      const reasons: Record<string, number> = {};
      const verifiedLocations: LocationPoint[] = [];
      const deliveryReceipts: ControlReceipt[] = [];
      const renewedCertificates = [];
      const sosRefs: SosRef[] = [];

      for (const frame of frameList) {
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
          if (result.record.channel === SOS_CHANNEL) {
            await deriveAndUpsertSosCreated(result.record);
          }
          // Delivery is deliberately a backend-signed control receipt. A mule can
          // carry it back via NAN; it never authorizes a third party to stop SOS TX.
          if (result.record.channel === "solicito-ayuda") {
            deliveryReceipts.push(await signControlReceipt({
              type: "delivery",
              targetSosId: result.record.id,
              targetSosAuthor: result.record.author,
              issuedAt: Date.now(),
              expiresAt: Date.now() + 30 * 24 * 60 * 60_000,
              issuerPublicKey: publicKeyHex,
            }, privateKey));
          }
        } else {
          duplicate++;
        }

        // Collect every verified SOS (fresh or re-uploaded) for control minting.
        // A re-upload means the victim is still advertising — a terminal-state
        // re-upload mints a backend-resolve stop order below. payload.nodeId is
        // sha256(pubkey)[0..4] hex (frame.ts), i.e. the control targetNodeId.
        if (result.record.channel === SOS_CHANNEL) {
          const p = result.record.payload as { nodeId?: string; msgId?: unknown };
          if (typeof p.nodeId === "string" && typeof p.msgId === "number") {
            const pubkeyHex = result.record.author.slice("device-".length);
            sosRefs.push({
              pubkeyHex,
              nodeIdHex: p.nodeId,
              msgId: p.msgId,
              fresh: added,
              sosId: deriveSosId(hexToBytes(pubkeyHex), p.msgId),
            });
          }
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

      // Mules request renewals for pre-authorized field keys. The allowlist is
      // server configuration, never learned from an untrusted ingest request.
      const requested = Array.isArray(request.body?.rescuerPublicKeys)
        ? request.body.rescuerPublicKeys.filter((v): v is string => typeof v === "string" && securityConfig.rescuerPublicKeys.has(v)).slice(0, 32)
        : [];
      for (const key of requested) {
        renewedCertificates.push(await issueRescuerCertificate(key, Date.now() + securityConfig.rescuerCertificateValidityMs, privateKey));
      }

      // Control frames the mule carried in: verify zero-trust, replay-admit once
      // per signature, emit SOS_CANCELLED for accepted self-cancels. Run BEFORE
      // minting so a self-cancel accepted this batch flips state to terminal and
      // a co-uploaded SOS for the same victim yields a backend-resolve stop order.
      const controlResult = controlList.length > 0
        ? await processControlFrames(controlList)
        : { accepted: 0, duplicate: 0, rejected: 0, reasons: {} as Record<string, number> };

      // Mint control frames for the mule to carry back into the mesh: a delivery
      // ack for each freshly-seen active SOS, and a backend-resolve stop order for
      // any SOS whose reconciled state is terminal yet is still being re-uploaded.
      const minted = sosRefs.length > 0 ? await mintControlFrames(sosRefs) : [];
      const certificateRevocationList = await issueCertificateRevocationList(
        securityConfig.revokedRescuerPublicKeys,
        securityConfig.rescuerCrlSequence,
        Date.now() + 24 * 60 * 60_000,
        privateKey,
      );

      return {
        success: true,
        ingested,
        duplicate,
        rejected,
        locationsIngested,
        reasons,
        deliveryReceipts,
        renewedCertificates,
        certificateRevocationList,
        controlAccepted: controlResult.accepted,
        controlDuplicate: controlResult.duplicate,
        controlRejected: controlResult.rejected,
        controlReasons: controlResult.reasons,
        controlFrames: minted.map((m) => m.frameB64),
      };
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

/**
 * Derive the deterministic SOS_CREATED LedgerEvent from a verified mesh frame
 * and upsert it into the ledger. eventId is deterministic over the payload, so
 * re-uploads of the same frame dedupe idempotently. The proof's authenticity
 * comes exclusively from the original device signature (encoding=ble-payload-v1);
 * the backend does not re-sign CREATED.
 */
async function deriveAndUpsertSosCreated(record: ChannelRecord): Promise<void> {
  const frameB64 = (record.payload as { frameB64?: unknown }).frameB64;
  if (typeof frameB64 !== "string") return;
  const full = Buffer.from(frameB64, "base64");
  const payloadBytes = Uint8Array.from(full.subarray(MESH_PAYLOAD_OFF, MESH_PAYLOAD_OFF + MESH_PAYLOAD_LEN));
  const pubkeyBytes = Uint8Array.from(full.subarray(MESH_PUBKEY_OFF, MESH_PUBKEY_OFF + MESH_PUBKEY_LEN));
  const sigBytes = Uint8Array.from(full.subarray(MESH_SIG_OFF, MESH_SIG_OFF + MESH_SIG_LEN));
  const messageId = (record.payload as { msgId?: unknown }).msgId;
  if (typeof messageId !== "number" || !Number.isFinite(messageId)) return;

  const sosId = deriveSosId(pubkeyBytes, messageId);
  const ledgerPayload: SosCreatedPayload = {
    payloadVersion: 1,
    backingRecordId: record.id,
    channel: SOS_CHANNEL,
    messageId,
    createdAt: record.timestamp,
  };
  const payloadHash = computePayloadHash(ledgerPayload);
  const proof: ChannelRecordProof = {
    proofType: "channel-record",
    proofVersion: 1,
    encoding: "ble-payload-v1",
    recordCanonicalBytes: payloadBytes,
    recordSignature: sigBytes,
    backingRecordId: record.id,
    messageId,
  };
  const unsigned: UnsignedLedgerEvent = {
    version: 1,
    sosId,
    type: "SOS_CREATED",
    previousSosEventHash: null,
    issuedAt: record.timestamp,
    issuerPublicKey: pubkeyBytes,
    payload: ledgerPayload,
    payloadHash,
    payloadVersion: 1,
    proof,
  };
  const event: LedgerEvent = { ...unsigned, eventId: computeEventId(unsigned) };
  try {
    await ledgerRepo.upsertEvent(event);
  } catch (err) {
    // Ledger failure must not break ingest. The frame is already persisted as a
    // ChannelRecord; reconcile will catch up on the next frame with same sosId.
    console.error("[ledger] SOS_CREATED upsert failed", err);
  }
}
