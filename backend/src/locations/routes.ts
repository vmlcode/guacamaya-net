import { FastifyInstance } from "fastify";
import { locationsRepo } from "../db/locationsRepo.js";

export async function locationRoutes(fastify: FastifyInstance) {
  // GET /locations?since=<ms>&deviceId=<id> — trajectory history for the moving map.
  //
  // Read-only. Location *ingestion* is no longer a separate trusted-JSON endpoint:
  // under the zero-trust data-mule model, points are derived from Ed25519-verified
  // mesh frames in POST /ingest (see channels/routes.ts + mesh/frame.ts). The
  // backend never persists a position it hasn't cryptographically authenticated.
  fastify.get<{ Querystring: { since?: string; deviceId?: string } }>(
    "/locations",
    async (request) => {
      const since = Number(request.query.since ?? 0);
      const deviceId = request.query.deviceId;
      return locationsRepo.getPoints(since, deviceId);
    }
  );
}
