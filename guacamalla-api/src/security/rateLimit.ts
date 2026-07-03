import type { FastifyReply, FastifyRequest } from "fastify";
import { securityConfig } from "./config.js";

/**
 * DeviceId-scoped leaky-bucket limiter.
 *
 * `@fastify/rate-limit` is IP-scoped — useless against a single host cycling
 * finder pubkeys. This one keys on the witness deviceId extracted from the
 * signed envelope, so a troll must rotate Ed25519 keypairs (and thus deviceIds)
 * to exceed the cap.
 */

interface BucketEntry {
  tokens: number;
  lastRefillMs: number;
}

const IDLE_PURGE_MS = 60 * 60 * 1000;
const MAX_ENTRIES = 10_000;

export class LeakyBucket {
  private entries = new Map<string, BucketEntry>();
  private lastPurgeMs = Date.now();

  constructor(
    private readonly capacity: number,
    private readonly refillPerSec: number
  ) {}

  take(key: string, now: number = Date.now()): { allowed: boolean; retryAfterMs: number; remaining: number } {
    this.maybePurge(now);

    const entry = this.entries.get(key);
    if (entry) {
      const elapsedSec = (now - entry.lastRefillMs) / 1000;
      const refilled = Math.min(this.capacity, entry.tokens + elapsedSec * this.refillPerSec);
      entry.tokens = refilled;
      entry.lastRefillMs = now;
    }

    const current = entry ?? { tokens: this.capacity, lastRefillMs: now };
    if (!entry) this.entries.set(key, current);

    if (current.tokens >= 1) {
      current.tokens -= 1;
      return { allowed: true, retryAfterMs: 0, remaining: Math.floor(current.tokens) };
    }

    const deficit = 1 - current.tokens;
    const retryAfterMs = Math.ceil((deficit / this.refillPerSec) * 1000);
    return { allowed: false, retryAfterMs, remaining: 0 };
  }

  private maybePurge(now: number): void {
    if (now - this.lastPurgeMs < IDLE_PURGE_MS / 4) return;
    this.lastPurgeMs = now;
    for (const [k, v] of this.entries) {
      if (now - v.lastRefillMs > IDLE_PURGE_MS) this.entries.delete(k);
    }
    if (this.entries.size > MAX_ENTRIES) {
      const sorted = [...this.entries.entries()].sort((a, b) => a[1].lastRefillMs - b[1].lastRefillMs);
      for (const [k] of sorted.slice(0, this.entries.size - MAX_ENTRIES)) {
        this.entries.delete(k);
      }
    }
  }

  /** Test-only helper. */
  reset(key: string): void {
    this.entries.delete(key);
  }
}

export const witnessResolveBucket = new LeakyBucket(
  securityConfig.resolve.perWitnessPerH,
  securityConfig.resolve.perWitnessPerH / 3600
);

declare module "fastify" {
  interface FastifyInstance {
    witnessResolveBucket?: LeakyBucket;
  }
}

/**
 * PreHandler that rate-limits the submitter (first witness in the envelope).
 * Expects the parsed body to already be on `request.body`; callers must run
 * envelope shape validation before this hook fires, or set `req.witnessDeviceId`
 * manually.
 */
export function perWitnessResolveLimit() {
  return async (request: FastifyRequest, reply: FastifyReply) => {
    const deviceId =
      (request as FastifyRequest & { witnessDeviceId?: string }).witnessDeviceId ??
      ((request.body as { witnesses?: { deviceId?: string }[] })?.witnesses?.[0]?.deviceId ?? "");

    if (!deviceId) return;

    const { allowed, retryAfterMs, remaining } = witnessResolveBucket.take(deviceId);
    reply.header("X-Resolve-Remaining", String(remaining));
    if (!allowed) {
      reply.header("Retry-After", String(Math.ceil(retryAfterMs / 1000)));
      return reply.code(429).send({
        accepted: false,
        status: "rejected",
        reason: "rate_limited",
        retryAfterMs,
      });
    }
  };
}
