import { FastifyInstance } from "fastify";
import { getLocationId } from "@guacamaya/shared";
import { locationsRepo } from "../db/locationsRepo.js";
import { broadcastLocation } from "../ws/server.js";

const MAX_FUTURE_SKEW_MS = 5 * 60 * 1000; // reject fixes more than 5 min in the future

function isValidPoint(item: unknown): item is {
  deviceId: string;
  lat: number;
  lon: number;
  timestamp: number;
  accuracy?: number;
} {
  if (!item || typeof item !== "object") return false;
  const p = item as Record<string, unknown>;
  return (
    typeof p.deviceId === "string" && p.deviceId.length > 0 &&
    typeof p.lat === "number" && isFinite(p.lat) && p.lat >= -90 && p.lat <= 90 &&
    typeof p.lon === "number" && isFinite(p.lon) && p.lon >= -180 && p.lon <= 180 &&
    typeof p.timestamp === "number" && p.timestamp > 0 &&
    p.timestamp <= Date.now() + MAX_FUTURE_SKEW_MS &&
    (p.accuracy === undefined || (typeof p.accuracy === "number" && isFinite(p.accuracy) && p.accuracy >= 0))
  );
}

export async function locationRoutes(fastify: FastifyInstance) {
  // POST /ingest/locations — data-mule batch upload
  fastify.post<{ Body: { locations?: unknown[] } }>(
    "/ingest/locations",
    async (request, reply) => {
      const { locations } = request.body;

      if (!locations || !Array.isArray(locations)) {
        return reply.code(400).send({ error: "Missing or invalid locations array" });
      }

      const valid = locations.filter(isValidPoint).map((p) => ({
        ...p,
        id: getLocationId(p),
      }));

      const ingested = await locationsRepo.addPoints(valid);

      for (const p of valid) {
        broadcastLocation(p);
      }

      return { success: true, ingested, received: locations.length };
    }
  );

  // GET /locations?since=<ms>&deviceId=<id> — for the moving map dashboard
  fastify.get<{ Querystring: { since?: string; deviceId?: string } }>(
    "/locations",
    async (request) => {
      const since = Number(request.query.since ?? 0);
      const deviceId = request.query.deviceId;
      return locationsRepo.getPoints(since, deviceId);
    }
  );
}
