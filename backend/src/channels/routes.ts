import { FastifyInstance } from "fastify";
import { ChannelId } from "@guacamaya/shared";
import { channelsRepo } from "../db/channelsRepo.js";
import { signRecord } from "../crypto/signer.js";
import { broadcastRecord } from "../ws/server.js";
import { publicKeyHex } from "../crypto/keys.js";

export async function channelRoutes(fastify: FastifyInstance) {
  // GET /pubkey -> returns backend's public identity key for client verification
  fastify.get("/pubkey", async () => {
    return { publicKey: publicKeyHex };
  });

  // GET /channels -> lists available emergency channels
  fastify.get("/channels", async () => {
    return [
      { id: "alertas", name: "Alertas Oficiales", verifiedOnly: true },
      { id: "refugios", name: "Refugios y Recursos", verifiedOnly: true },
      { id: "ayuda-medica", name: "Ayuda Médica", verifiedOnly: true },
      { id: "estoy-bien", name: "Estoy Bien (Comunidad)", verifiedOnly: false },
      { id: "solicito-ayuda", name: "Solicito Ayuda (Comunidad)", verifiedOnly: false },
    ];
  });

  // GET /channels/:id/records -> retrieves records since a given timestamp
  fastify.get<{ Params: { id: string }; Querystring: { since?: string } }>(
    "/channels/:id/records",
    async (request, reply) => {
      const channelId = request.params.id as ChannelId;
      const since = Number(request.query.since ?? 0);
      return channelsRepo.getRecords(channelId, since);
    }
  );

  // POST /channels/:id/records -> creates and signs a new official update
  fastify.post<{ Params: { id: string }; Body: { payload: unknown } }>(
    "/channels/:id/records",
    async (request, reply) => {
      const channelId = request.params.id as ChannelId;
      const { payload } = request.body;

      if (payload === undefined) {
        return reply.code(400).send({ error: "Missing payload in request body" });
      }

      const unsigned = {
        channel: channelId,
        timestamp: Date.now(),
        ttl: 3, // Default mesh saltos limit
        author: "backend",
        payload,
      };

      try {
        const signed = await signRecord(unsigned);
        await channelsRepo.addRecord(signed);
        broadcastRecord(signed); // Push update to live websockets
        return signed;
      } catch (err) {
        request.log.error(err, "Failed to sign official record");
        return reply.code(500).send({ error: "Failed to sign official record" });
      }
    }
  );

  // POST /ingest -> accepts data-mule uploads from mesh-connected devices
  fastify.post<{ Body: { records?: any[] } }>(
    "/ingest",
    async (request, reply) => {
      const { records } = request.body;

      if (!records || !Array.isArray(records)) {
        return reply.code(400).send({ error: "Missing or invalid records array" });
      }

      let ingestedCount = 0;
      for (const rec of records) {
        if (
          rec &&
          typeof rec === "object" &&
          typeof rec.id === "string" &&
          typeof rec.channel === "string" &&
          typeof rec.timestamp === "number" &&
          typeof rec.author === "string" &&
          rec.payload !== undefined
        ) {
          const added = await channelsRepo.addRecord(rec);
          if (added) {
            ingestedCount++;
            broadcastRecord(rec); // Forward to websocket connections
          }
        }
      }

      return { success: true, ingested: ingestedCount };
    }
  );
}
