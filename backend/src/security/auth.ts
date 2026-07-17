import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";

function extractToken(request: FastifyRequest): string | undefined {
  const header = request.headers["x-api-key"];
  if (typeof header === "string" && header.length > 0) return header;

  const auth = request.headers.authorization;
  if (typeof auth === "string" && auth.startsWith("Bearer ")) {
    return auth.slice(7);
  }
  return undefined;
}

function keysMatch(provided: string, expected: string): boolean {
  // Compare fixed-size digests so different-length inputs follow the same path.
  // SHA-256 is used only to normalize the input length; timingSafeEqual remains
  // the final comparison primitive.
  const a = createHash("sha256").update(provided, "utf8").digest();
  const b = createHash("sha256").update(expected, "utf8").digest();
  return timingSafeEqual(a, b);
}

export function requireApiKey(expectedKey: string | undefined, scope: string) {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    if (!expectedKey) {
      request.log.error(`Missing API key configuration for ${scope}`);
      return reply.code(503).send({ error: "Server misconfigured" });
    }

    const token = extractToken(request);
    if (!token || !keysMatch(token, expectedKey)) {
      return reply.code(401).send({ error: "Unauthorized" });
    }
  };
}

export function verifyWsToken(token: string | null, expectedKey: string | undefined): boolean {
  if (!expectedKey || !token) return false;
  return keysMatch(token, expectedKey);
}
