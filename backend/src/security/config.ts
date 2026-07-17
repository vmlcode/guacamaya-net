import { randomBytes } from "node:crypto";

function parseOrigins(raw: string | undefined, production: boolean): string[] | true {
  if (!raw || raw.trim() === "*") {
    if (production) {
      throw new Error("CORS_ORIGINS must be an explicit comma-separated allowlist in production");
    }
    return true;
  }
  const list = raw.split(",").map((s) => s.trim()).filter(Boolean);
  if (list.length === 0) {
    throw new Error("CORS_ORIGINS must contain at least one origin");
  }
  for (const origin of list) {
    let parsed: URL;
    try {
      parsed = new URL(origin);
    } catch {
      throw new Error(`Invalid CORS origin: ${origin}`);
    }
    if (parsed.origin !== origin || (parsed.protocol !== "https:" && parsed.protocol !== "http:")) {
      throw new Error(`CORS origin must be an exact http(s) origin without a path: ${origin}`);
    }
    if (production && parsed.protocol !== "https:") {
      throw new Error(`CORS origin must use HTTPS in production: ${origin}`);
    }
  }
  return list;
}

function resolveKey(envName: string, fallback?: string): string | undefined {
  const v = process.env[envName]?.trim();
  if (v) return v;
  return fallback;
}

const isProduction = process.env.NODE_ENV === "production";

function boundedNumber(
  envName: string,
  fallback: number,
  min: number,
  max: number,
  integer = false,
): number {
  const raw = process.env[envName];
  const parsed = raw === undefined || raw.trim() === "" ? fallback : Number(raw);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${envName} must be a finite number`);
  }
  const value = integer ? Math.trunc(parsed) : parsed;
  if (value < min || value > max) {
    throw new Error(`${envName} must be between ${min} and ${max}`);
  }
  return value;
}

/** Ephemeral dev key — generated once without ever exposing it in logs. */
function devFallback(label: string): string | undefined {
  if (isProduction) return undefined;
  const key = randomBytes(32).toString("hex");
  console.warn(
    `[guacamaya-net] ${label} not set — using an unlogged ephemeral dev key; ` +
      `set ${label} explicitly to authenticate development clients`,
  );
  return key;
}

export const securityConfig = {
  isProduction,

  corsOrigins: parseOrigins(process.env.CORS_ORIGINS, isProduction),

  /** Signs official channel records (POST /channels/:id/records). */
  adminApiKey:
    resolveKey("GUACAMAYA_ADMIN_KEY") ??
    resolveKey("GUACAMAYA_API_KEY") ??
    devFallback("GUACAMAYA_ADMIN_KEY"),

  /** Reads trajectories and sensitive WS channels (GET /locations, WS subscribe). */
  readApiKey:
    resolveKey("GUACAMAYA_READ_KEY") ??
    resolveKey("GUACAMAYA_ADMIN_KEY") ??
    resolveKey("GUACAMAYA_API_KEY"),

  /** WebSocket upgrade token (query `?token=` or header). Falls back to read key. */
  wsApiKey: resolveKey("GUACAMAYA_WS_KEY"),

  maxIngestBatch: boundedNumber("MAX_INGEST_BATCH", 200, 1, 1000, true),
  maxFrameB64Length: boundedNumber("MAX_FRAME_B64_LENGTH", 256, 64, 4096, true),
  maxOfficialPayloadBytes: boundedNumber("MAX_OFFICIAL_PAYLOAD_BYTES", 16_384, 1, 1_048_576, true),

  /** Allowlisted field rescuers may receive a fresh certificate through a mule ingest. */
  rescuerPublicKeys: new Set((process.env.RESCUER_PUBLIC_KEYS ?? "").split(",").map((v) => v.trim()).filter((v) => /^[0-9a-f]{64}$/.test(v))),
  rescuerCertificateValidityMs: boundedNumber("RESCUER_CERTIFICATE_VALIDITY_H", 168, 1, 168) * 60 * 60_000,
  revokedRescuerPublicKeys: [...new Set((process.env.REVOKED_RESCUER_PUBLIC_KEYS ?? "").split(",").map((v) => v.trim()).filter((v) => /^[0-9a-f]{64}$/.test(v)))],
  rescuerCrlSequence: boundedNumber("RESCUER_CRL_SEQUENCE", 0, 0, Number.MAX_SAFE_INTEGER, true),

  globalRateLimit: { max: 100, timeWindow: "1 minute" as const },
  ingestRateLimit: { max: 30, timeWindow: "1 minute" as const },
  officialWriteRateLimit: { max: 20, timeWindow: "1 minute" as const },
  waitlistRateLimit: { max: 10, timeWindow: "1 minute" as const },

  /** Resolve flow — finder co-signed disarm of an active SOS. */
  resolve: {
    /** Legacy quorum endpoint can silence an SOS; never expose it in production. */
    legacyEndpointEnabled:
      !isProduction && process.env.GUACAMAYA_LEGACY_RESOLVE_ENDPOINT === "1",
    /**
     * Legacy /resolve broadcast: the receipt WS push (broadcastResolve) and the
     * "resuelto" channel record (emitResueltoRecord). These predate the ledger and
     * duplicate its verdict. Off by default — the moderated ledger state is now the
     * single source of visible effects (stop-victim-transmission control frames are
     * minted from terminal ledger state on the mule path). Forced off in production;
     * set GUACAMAYA_LEGACY_RESOLVE_BROADCAST=1 to re-enable in dev for back-compat.
     */
    legacyBroadcast: isProduction ? false : process.env.GUACAMAYA_LEGACY_RESOLVE_BROADCAST === "1",
    quorumRequired: boundedNumber("RESOLVE_QUORUM_REQUIRED", 2, 1, 100, true),
    quorumTotal: boundedNumber("RESOLVE_QUORUM_TOTAL", 3, 1, 100, true),
    geoRadiusKm: boundedNumber("RESOLVE_GEO_RADIUS_KM", 5, 0.01, 1000),
    targetMaxAgeH: boundedNumber("RESOLVE_TARGET_MAX_AGE_H", 72, 1, 24 * 365),
    witnessMaxAgeMs: boundedNumber("RESOLVE_WITNESS_MAX_AGE_MS", 10 * 60_000, 1000, 24 * 60 * 60_000, true),
    clockSkewMs: boundedNumber("RESOLVE_CLOCK_SKEW_MS", 2 * 60_000, 0, 60 * 60_000, true),
    cooldownMin: boundedNumber("RESOLVE_COOLDOWN_MIN", 15, 0, 24 * 60),
    perWitnessPerH: boundedNumber("RESOLVE_PER_WITNESS_PER_H", 5, 1, 10_000, true),
    evidenceTtlMs: boundedNumber("RESOLVE_EVIDENCE_TTL_MS", 300_000, 1000, 24 * 60 * 60_000, true),
    maxImageBytes: boundedNumber("RESOLVE_MAX_IMAGE_BYTES", 8 * 1024 * 1024, 1024, 25 * 1024 * 1024, true),
    evidenceRequireAuth: isProduction
      ? process.env.RESOLVE_EVIDENCE_REQUIRE_AUTH !== "false"
      : process.env.RESOLVE_EVIDENCE_REQUIRE_AUTH === "true",
    evidenceDir: process.env.EVIDENCE_DIR ?? ".evidence",
    evidenceBucket: process.env.SUPABASE_EVIDENCE_BUCKET ?? "resolve-evidence",
  },
};

export function effectiveReadKey(): string | undefined {
  return securityConfig.readApiKey ?? securityConfig.adminApiKey;
}

export function effectiveWsKey(): string | undefined {
  return securityConfig.wsApiKey ?? effectiveReadKey();
}

if (isProduction && !securityConfig.adminApiKey) {
  throw new Error("GUACAMAYA_ADMIN_KEY is required when NODE_ENV=production");
}

if (securityConfig.resolve.quorumRequired > securityConfig.resolve.quorumTotal) {
  throw new Error("RESOLVE_QUORUM_REQUIRED cannot exceed RESOLVE_QUORUM_TOTAL");
}

if (isProduction && securityConfig.adminApiKey!.length < 32) {
  throw new Error("GUACAMAYA_ADMIN_KEY must contain at least 32 characters in production");
}
