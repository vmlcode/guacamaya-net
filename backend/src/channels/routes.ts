import { FastifyInstance } from "fastify";
import { ChannelId, LocationPoint } from "@guacamaya/shared";
import { channelsRepo } from "../db/channelsRepo.js";
import { locationsRepo } from "../db/locationsRepo.js";
import { signRecord } from "../crypto/signer.js";
import { broadcastRecord, broadcastLocation } from "../ws/server.js";
import { publicKeyHex } from "../crypto/keys.js";
import { decodeAndVerifyFrame } from "../mesh/frame.js";

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

  // POST /ingest -> data-mule uploads from the Guacamaya Android mesh.
  //
  // The phone sends an array of Base64-encoded BLE frames (the 118 B Guacamaya frame:
  // 22 B payload + 32 B pubkey + 64 B signature). ZERO TRUST: every frame is
  // decoded and its Ed25519 signature is RE-VERIFIED here before anything is
  // persisted — the backend never takes the client's word for authenticity.
  //
  // Every verified frame also carries a geolocated point (lat/lon live inside the
  // signed 22 B payload), so the moving-map history is populated from the SAME
  // zero-trust path — there is no separate trusted location ingest endpoint.
  //
  // Body: { frames: string[] }  (legacy { records: [...] } JSON ingestion removed)
  fastify.post<{ Body: { frames?: unknown } }>(
    "/ingest",
    async (request, reply) => {
      const { frames } = request.body;

      if (!Array.isArray(frames)) {
        return reply.code(400).send({ error: "Missing or invalid 'frames' array" });
      }

      let ingested = 0;
      let duplicate = 0;
      let rejected = 0;
      const reasons: Record<string, number> = {};
      const verifiedLocations: LocationPoint[] = [];

      for (const frame of frames) {
        if (typeof frame !== "string") {
          rejected++;
          reasons["not a string"] = (reasons["not a string"] ?? 0) + 1;
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
          broadcastRecord(result.record); // push to live websocket subscribers
        } else {
          duplicate++;
        }

        // Only frames that passed Ed25519 verification reach here — the location
        // is authenticated to its origin device, not trusted from the client.
        if (result.location) {
          verifiedLocations.push(result.location);
        }
      }

      // Persist the geolocated points in one batch. Dedup is by `id` (content
      // hash) just like records, so overlapping mule uploads collapse cleanly.
      let locationsIngested = 0;
      if (verifiedLocations.length > 0) {
        locationsIngested = await locationsRepo.addPoints(verifiedLocations);
        for (const point of verifiedLocations) {
          broadcastLocation(point); // push to live "locations" subscribers
        }
      }

      if (rejected > 0) {
        request.log.warn({ rejected, reasons }, "rejected unverifiable mesh frames");
      }

      return { success: true, ingested, duplicate, rejected, locationsIngested, reasons };
    }
  );
}
