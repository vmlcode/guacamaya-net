import type { ChannelRecord } from "@guacamaya/shared";

/**
 * Channels whose records are publicly readable (no auth on `GET
 * /channels/:id/records`) but carry victim GPS derived from signed mesh frames.
 *
 * The exact, full-precision position of these reports is sensitive: it is the
 * location of someone emitting an SOS (violence/medical). The authenticated,
 * read-key-gated `GET /locations` endpoint is the canonical source of exact
 * positions; this public channel-read path must coarsen them so the open
 * endpoint cannot be used to track or target vulnerable people.
 *
 * Official operator channels (alertas/refugios/ayuda-medica) are intentionally
 * public broadcasts and are not coarsened.
 */
const COARSE_LOCATION_CHANNELS = new Set(["solicito-ayuda", "estoy-bien"]);

/**
 * ~1.1 km at the equator (2 decimal places ≈ 0.01° ≈ 1.11 km lat; ~1.1 km lon
 * at Venezuela's latitude). Coarse enough to deny precise targeting while still
 * useful for "there is an SOS in this area" situational awareness.
 */
function coarsenCoord(v: number): number {
  return Math.round(v * 100) / 100;
}

/**
 * Strip exact-position vectors from a community SOS record for the public,
 * unauthenticated channel-read response:
 *   - round lat/lon to ~1 km
 *   - drop `frameB64` — the raw signed frame encodes exact lat/lon in bytes
 *     0..7, so leaving it would defeat the coordinate coarsening.
 *
 * Non-sensitive channels and records without a coordinate payload pass through
 * unchanged. The stored record (DB / `/locations`) is never mutated — this only
 * shapes the public HTTP response.
 */
export function sanitizeRecordForPublic(record: ChannelRecord): ChannelRecord {
  if (!COARSE_LOCATION_CHANNELS.has(record.channel)) return record;

  const payload = record.payload;
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return record;

  const { frameB64: _drop, lat, lon, ...rest } = payload as Record<string, unknown>;
  const sanitized: Record<string, unknown> = { ...rest, locationPrecision: "coarse" };
  if (typeof lat === "number" && Number.isFinite(lat)) sanitized.lat = coarsenCoord(lat);
  if (typeof lon === "number" && Number.isFinite(lon)) sanitized.lon = coarsenCoord(lon);

  return { ...record, payload: sanitized };
}

export function sanitizeRecordsForPublic(records: ChannelRecord[]): ChannelRecord[] {
  return records.map(sanitizeRecordForPublic);
}
