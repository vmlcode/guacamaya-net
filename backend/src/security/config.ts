import { randomBytes } from "node:crypto";

function parseOrigins(raw: string | undefined): string[] | true {
  if (!raw || raw.trim() === "*") return true;
  const list = raw.split(",").map((s) => s.trim()).filter(Boolean);
  return list.length > 0 ? list : true;
}

function resolveKey(envName: string, fallback?: string): string | undefined {
  const v = process.env[envName]?.trim();
  if (v) return v;
  return fallback;
}

const isProduction = process.env.NODE_ENV === "production";

/** Ephemeral dev key — logged once at startup when no env key is set. */
function devFallback(label: string): string | undefined {
  if (isProduction) return undefined;
  const key = randomBytes(32).toString("hex");
  console.warn(
    `[guacamaya-net] ${label} not set — using ephemeral dev key (set ${label} in production):\n  ${key}`,
  );
  return key;
}

export const securityConfig = {
  isProduction,

  corsOrigins: parseOrigins(process.env.CORS_ORIGINS),

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

  maxIngestBatch: Math.min(Number(process.env.MAX_INGEST_BATCH ?? 200), 1000),
  maxFrameB64Length: Number(process.env.MAX_FRAME_B64_LENGTH ?? 256),
  maxOfficialPayloadBytes: Number(process.env.MAX_OFFICIAL_PAYLOAD_BYTES ?? 16_384),

  globalRateLimit: { max: 100, timeWindow: "1 minute" as const },
  ingestRateLimit: { max: 30, timeWindow: "1 minute" as const },
  officialWriteRateLimit: { max: 20, timeWindow: "1 minute" as const },
  waitlistRateLimit: { max: 10, timeWindow: "1 minute" as const },

  /** Resolve flow — finder co-signed disarm of an active SOS. */
  resolve: {
    quorumRequired: Math.max(1, Number(process.env.RESOLVE_QUORUM_REQUIRED ?? 2)),
    quorumTotal: Math.max(1, Number(process.env.RESOLVE_QUORUM_TOTAL ?? 3)),
    geoRadiusKm: Number(process.env.RESOLVE_GEO_RADIUS_KM ?? 5),
    targetMaxAgeH: Number(process.env.RESOLVE_TARGET_MAX_AGE_H ?? 72),
    cooldownMin: Number(process.env.RESOLVE_COOLDOWN_MIN ?? 15),
    perWitnessPerH: Number(process.env.RESOLVE_PER_WITNESS_PER_H ?? 5),
    evidenceTtlMs: Number(process.env.RESOLVE_EVIDENCE_TTL_MS ?? 300_000),
    maxImageBytes: Number(process.env.RESOLVE_MAX_IMAGE_BYTES ?? 8 * 1024 * 1024),
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
