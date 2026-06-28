import { FastifyInstance } from "fastify";
import { locationsRepo } from "../db/locationsRepo.js";
import { requireApiKey } from "../security/auth.js";
import { effectiveReadKey } from "../security/config.js";
import { isValidDeviceIdFilter, parseSinceParam } from "../security/validation.js";

export async function locationRoutes(fastify: FastifyInstance) {
  fastify.get<{ Querystring: { since?: string; deviceId?: string } }>(
    "/locations",
    { preHandler: requireApiKey(effectiveReadKey(), "location history") },
    async (request, reply) => {
      const since = parseSinceParam(request.query.since);
      const deviceId = request.query.deviceId;

      if (!isValidDeviceIdFilter(deviceId)) {
        return reply.code(400).send({ error: "Invalid deviceId filter" });
      }

      return locationsRepo.getPoints(since, deviceId);
    },
  );
}
