import type { FastifyInstance } from "fastify";
import { SOS_ID_RE, EVENT_ID_RE } from "@guacamaya/shared";
import { ledgerRepo, type LedgerCursor } from "./ledgerRepo.js";

const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 500;

export async function ledgerRoutes(fastify: FastifyInstance) {
  fastify.get<{ Params: { sosId: string } }>(
    "/ledger/state/:sosId",
    async (request, reply) => {
      const { sosId } = request.params;
      if (!SOS_ID_RE.test(sosId)) {
        return reply.code(400).send({ error: "INVALID_SOS_ID" });
      }
      const state = await ledgerRepo.getSosState(sosId);
      if (!state) return reply.code(404).send({ error: "NOT_FOUND" });
      return state;
    },
  );

  fastify.get<{ Params: { sosId: string } }>(
    "/ledger/events/:sosId",
    async (request, reply) => {
      const { sosId } = request.params;
      if (!SOS_ID_RE.test(sosId)) {
        return reply.code(400).send({ error: "INVALID_SOS_ID" });
      }
      const stored = await ledgerRepo.getEventsBySosId(sosId);
      const attached = stored.filter((s) => s.chainStatus === "attached");
      const orphans = stored.filter((s) => s.chainStatus === "orphan");
      const conflicts = stored.filter((s) => s.chainStatus === "conflict");
      const decorate = (s: { event: unknown; receivedAt: number; chainStatus: string }) => ({
        ...(serializeEvent(s.event) as Record<string, unknown>),
        receivedAt: s.receivedAt,
        chainStatus: s.chainStatus,
      });
      return {
        sosId,
        attached: attached.map(decorate),
        orphans: orphans.map(decorate),
        conflicts: conflicts.map(decorate),
      };
    },
  );

  fastify.get<{
    Querystring: { afterReceivedAt?: string; afterEventId?: string; limit?: string };
  }>("/ledger/events", async (request) => {
    const rawLimit = Number(request.query.limit ?? DEFAULT_LIMIT);
    const limit = Number.isFinite(rawLimit)
      ? Math.max(1, Math.min(MAX_LIMIT, Math.floor(rawLimit)))
      : DEFAULT_LIMIT;

    let cursor: LedgerCursor | null = null;
    const afterReceivedAt = request.query.afterReceivedAt;
    const afterEventId = request.query.afterEventId;
    if (afterReceivedAt !== undefined && afterEventId !== undefined) {
      const receivedAt = Number(afterReceivedAt);
      if (!Number.isFinite(receivedAt)) {
        return { events: [], nextCursor: null };
      }
      if (!EVENT_ID_RE.test(afterEventId)) {
        return { events: [], nextCursor: null };
      }
      cursor = { receivedAt, eventId: afterEventId };
    }

    const items = await ledgerRepo.listEventsAfter(cursor, limit);
    const events = items.map((s) => serializeEvent(s.event));
    const nextCursor =
      items.length === limit && items.length > 0
        ? {
            receivedAt: items[items.length - 1]!.receivedAt,
            eventId: items[items.length - 1]!.event.eventId,
          }
        : null;
    return { events, nextCursor };
  });

  fastify.post("/ledger/events", async (_request, reply) => {
    return reply.code(501).send({
      error: "NOT_IMPLEMENTED",
      reason: "peer-to-peer event transport not enabled in this build",
    });
  });
}

function serializeEvent(event: unknown) {
  // LedgerEvent contains Uint8Array fields (issuerPublicKey, payloadHash, proof
  // bytes, optional eventSignature). JSON.stringify coerces Uint8Array to
  // {"0":n,"1":n,...}; we want hex. Use the canonical wire form via toJsonValue.
  return toJsonSerializable(event);
}

function toJsonSerializable(v: unknown): unknown {
  if (v instanceof Uint8Array) return { $bytes: bytesToHex(v) };
  if (Array.isArray(v)) return v.map(toJsonSerializable);
  if (v && typeof v === "object") {
    const out: Record<string, unknown> = {};
    const src = v as Record<string, unknown>;
    for (const k of Object.keys(src)) out[k] = toJsonSerializable(src[k]);
    return out;
  }
  return v;
}

function bytesToHex(bytes: Uint8Array): string {
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += bytes[i]!.toString(16).padStart(2, "0");
  return out;
}
