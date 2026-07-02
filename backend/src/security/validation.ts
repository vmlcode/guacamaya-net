import { securityConfig } from "./config.js";
import type { ResolveEnvelope, ResolveWitness } from "@guacamaya/shared";

const VALID_CHANNEL_IDS = new Set([
  "alertas",
  "refugios",
  "ayuda-medica",
  "estoy-bien",
  "solicito-ayuda",
  "resuelto",
]);

export function isValidChannelId(id: string): boolean {
  return VALID_CHANNEL_IDS.has(id);
}

export function validateIngestBatch(frames: unknown): frames is string[] {
  return Array.isArray(frames) && frames.length <= securityConfig.maxIngestBatch;
}

export function isValidFrameB64(frame: string): boolean {
  if (frame.length > securityConfig.maxFrameB64Length) return false;
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(frame)) return false;
  return true;
}

export function validateOfficialPayload(payload: unknown): payload is Record<string, unknown> {
  if (payload === null || typeof payload !== "object" || Array.isArray(payload)) return false;
  try {
    const size = JSON.stringify(payload).length;
    return size > 0 && size <= securityConfig.maxOfficialPayloadBytes;
  } catch {
    return false;
  }
}

export function parseSinceParam(raw: string | undefined): number {
  const n = Number(raw ?? 0);
  if (!Number.isFinite(n) || n < 0) return 0;
  return Math.floor(n);
}

const DEVICE_ID_RE = /^device-[0-9a-f]{64}$/;

export function isValidDeviceIdFilter(id: string | undefined): id is string | undefined {
  if (id === undefined || id === "") return true;
  return DEVICE_ID_RE.test(id);
}

// ─── Waitlist validators ──────────────────────────────────────────────────────

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const MAX_WAITLIST_COUNTRY_LENGTH = 60;

export function isValidEmail(email: string): boolean {
  return email.length > 0 && email.length <= 254 && EMAIL_RE.test(email);
}

export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

// The landing page's country field is free text (not a fixed <select>), so
// this only bounds length — any typed country name is accepted.
export function isValidWaitlistCountry(country: unknown): country is string {
  return typeof country === "string" && country.length <= MAX_WAITLIST_COUNTRY_LENGTH;
}

// ─── Resolve flow validators ──────────────────────────────────────────────────

const HEX64_RE = /^[0-9a-f]{64}$/;
const HEX128_RE = /^[0-9a-f]{128}$/;

export function parseImageHash(h: unknown): string | null {
  return typeof h === "string" && HEX64_RE.test(h) ? h : null;
}

export function isValidWitnessEntry(w: unknown): w is ResolveWitness {
  if (!w || typeof w !== "object") return false;
  const o = w as Record<string, unknown>;
  if (typeof o.deviceId !== "string" || !DEVICE_ID_RE.test(o.deviceId)) return false;
  if (typeof o.pubkey !== "string" || !HEX64_RE.test(o.pubkey)) return false;
  if (typeof o.sig !== "string" || !HEX128_RE.test(o.sig)) return false;
  if (typeof o.lat !== "number" || !Number.isFinite(o.lat) || o.lat < -90 || o.lat > 90) return false;
  if (typeof o.lon !== "number" || !Number.isFinite(o.lon) || o.lon < -180 || o.lon > 180) return false;
  if (typeof o.ts !== "number" || !Number.isFinite(o.ts) || !Number.isInteger(o.ts)) return false;
  if (typeof o.imageHash !== "string" || !HEX64_RE.test(o.imageHash)) return false;
  if (o.uploadToken !== undefined && typeof o.uploadToken !== "string") return false;
  if (o.uploadToken !== undefined && !/^\d+\.[0-9a-f]+$/.test(o.uploadToken)) return false;
  if (o.macObservationHashes !== undefined) {
    if (!Array.isArray(o.macObservationHashes)) return false;
    for (const m of o.macObservationHashes) {
      if (typeof m !== "string" || !HEX64_RE.test(m)) return false;
    }
  }
  return true;
}

export function isValidResolveEnvelope(env: unknown): env is ResolveEnvelope {
  if (!env || typeof env !== "object") return false;
  const o = env as Record<string, unknown>;
  if (typeof o.targetSosId !== "string" || !HEX64_RE.test(o.targetSosId)) return false;
  if (typeof o.targetSosAuthor !== "string" || !DEVICE_ID_RE.test(o.targetSosAuthor)) return false;
  if (typeof o.submittedAt !== "number" || !Number.isFinite(o.submittedAt) || !Number.isInteger(o.submittedAt)) return false;
  if (o.note !== undefined && (typeof o.note !== "string" || o.note.length > 512)) return false;
  if (!Array.isArray(o.witnesses)) return false;
  if (o.witnesses.length < 1 || o.witnesses.length > securityConfig.resolve.quorumTotal) return false;
  for (const w of o.witnesses) {
    if (!isValidWitnessEntry(w)) return false;
  }
  // Distinct deviceIds within the array
  const seen = new Set<string>();
  for (const w of o.witnesses as ResolveWitness[]) {
    if (seen.has(w.deviceId)) return false;
    seen.add(w.deviceId);
  }
  return true;
}
