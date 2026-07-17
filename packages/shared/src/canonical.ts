import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";

/**
 * Deterministic JSON serialization used by the ledger event hash and the
 * payloadHash. Stable under key reordering; rejects values that JSON cannot
 * represent unambiguously. Mirrors the sorted-key style of control.ts but
 * supports Uint8Array (proofs carry signatures and raw BLE payloads).
 */
export function canonicalJsonBytes(value: unknown): Uint8Array {
  return new TextEncoder().encode(canonicalJsonString(value));
}

export function canonicalJsonString(value: unknown): string {
  return serialize(value);
}

function serialize(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("canonical: non-finite number");
    return String(value);
  }
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "bigint") throw new Error("canonical: bigint rejected");
  if (typeof value === "undefined") throw new Error("canonical: undefined rejected");
  if (value instanceof Uint8Array) {
    return `{"$bytes":"${bytesToHex(value)}"}`;
  }
  if (Array.isArray(value)) {
    return `[${value.map(serialize).join(",")}]`;
  }
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  const body = keys.map((k) => `${JSON.stringify(k)}:${serialize(obj[k])}`).join(",");
  return `{${body}}`;
}

export function hashCanonical(value: unknown): Uint8Array {
  return sha256(canonicalJsonBytes(value));
}

export function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i]!;
  return diff === 0;
}

export const EVENT_ID_RE = /^[0-9a-f]{64}$/;
export const SOS_ID_RE = /^[0-9a-f]{64}$/;

export type EventId = string;
export type SosId = string;
