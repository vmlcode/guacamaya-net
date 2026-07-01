import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { supabase, isSupabaseConfigured } from "../db/supabase.js";
import { securityConfig } from "../security/config.js";
import { hashImage } from "@guacamaya/shared";
import { publicKeyHex } from "../crypto/keys.js";

/**
 * Evidence-image storage abstraction.
 *
 * - Dev (no Supabase): bytes land under EVIDENCE_DIR (default backend/.evidence/),
 *   filename `<imageHash>.<ext>`. The dir is created on first write.
 * - Prod: bytes go to the configured Supabase Storage bucket.
 *
 * Each upload returns an HMAC `uploadToken` binding the image hash to a short
 * witness window (default 5 min). POST /resolve re-derives the token from the
 * envelope's imageHash and rejects mismatch — so a troll can't precompute a
 * hash for a fake image and submit it later.
 */

const EXT_BY_MAGIC: Array<{ magic: number[]; ext: string }> = [
  { magic: [0xff, 0xd8, 0xff], ext: "jpg" },
  { magic: [0x89, 0x50, 0x4e, 0x47], ext: "png" },
  { magic: [0x47, 0x49, 0x46, 0x38], ext: "gif" },
  { magic: [0x42, 0x4d], ext: "bmp" },
  { magic: [0x52, 0x49, 0x46, 0x46], ext: "webp" }, // RIFF — could be webp or wav; assume webp
];

function detectExt(bytes: Uint8Array): string {
  for (const { magic, ext } of EXT_BY_MAGIC) {
    if (bytes.length >= magic.length && magic.every((b, i) => bytes[i] === b)) return ext;
  }
  return "bin";
}

/** HMAC key — the backend's stable public key hex (ephemeral in dev). */
function hmacKey(): string {
  return publicKeyHex;
}

export function makeUploadToken(imageHash: string, expiresAtMs: number): string {
  const mac = createHmac("sha256", hmacKey())
    .update(`${imageHash}:${expiresAtMs}`)
    .digest("hex");
  return `${expiresAtMs}.${mac}`;
}

export function verifyUploadToken(token: string, imageHash: string, now: number = Date.now()): boolean {
  const parts = token.split(".");
  if (parts.length !== 2) return false;
  const expiresAt = Number(parts[0]);
  if (!Number.isFinite(expiresAt)) return false;
  if (expiresAt <= now) return false;
  const expected = makeUploadToken(imageHash, expiresAt);
  // Constant-time compare — V8's optimizer can break hand-rolled XOR loops.
  // Use crypto.timingSafeEqual for parity with auth.ts API-key compare.
  const a = Buffer.from(token);
  const b = Buffer.from(expected);
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}

export interface StoredEvidence {
  imageHash: string;
  storageKey: string;
  uploadToken: string;
  expiresAtMs: number;
}

export async function storeEvidence(imageBytes: Uint8Array): Promise<StoredEvidence> {
  const imageHash = hashImage(imageBytes);
  const ext = detectExt(imageBytes);
  const storageKey = isSupabaseConfigured ? `${imageHash}.${ext}` : join(securityConfig.resolve.evidenceDir, `${imageHash}.${ext}`);

  if (isSupabaseConfigured && supabase) {
    const { error } = await supabase
      .storage
      .from(securityConfig.resolve.evidenceBucket)
      .upload(storageKey, imageBytes, { upsert: true, contentType: `image/${ext === "jpg" ? "jpeg" : ext}` });
    if (error) throw error;
  } else {
    await mkdir(dirname(storageKey), { recursive: true });
    await writeFile(storageKey, imageBytes);
  }

  const expiresAtMs = Date.now() + securityConfig.resolve.evidenceTtlMs;
  return {
    imageHash,
    storageKey,
    uploadToken: makeUploadToken(imageHash, expiresAtMs),
    expiresAtMs,
  };
}

/**
 * Reads the stored image bytes back so the route can re-hash and confirm the
 * witness envelope's imageHash matches what is on disk/bucket. Returns null if
 * the file is missing (troll submitted a hash with no prior upload).
 */
export async function readEvidence(imageHash: string, ext: string = "bin"): Promise<Uint8Array | null> {
  if (isSupabaseConfigured && supabase) {
    const path = `${imageHash}.${ext}`;
    const { data, error } = await supabase
      .storage
      .from(securityConfig.resolve.evidenceBucket)
      .download(path);
    if (error || !data) return null;
    return new Uint8Array(await data.arrayBuffer());
  }
  // fs path — we don't know ext ahead of time, so try common ones.
  const { readFile } = await import("node:fs/promises");
  const dir = securityConfig.resolve.evidenceDir;
  for (const candidate of [ext, "jpg", "png", "webp", "gif", "bmp", "bin"]) {
    try {
      return new Uint8Array(await readFile(join(dir, `${imageHash}.${candidate}`)));
    } catch {
      // try next
    }
  }
  return null;
}

/** Test-only helper to randomize HMAC key. */
export function _ephemeralKey(): string {
  return randomBytes(32).toString("hex");
}
