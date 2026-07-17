import {
  verifyControlFrame,
  type VerifyControlFrameOptions,
  type ControlFrameResult,
} from "@guacamaya/shared";
import { publicKeyHex } from "../crypto/keys.js";

/**
 * Server-side decoder + verifier for GuacaMalla control frames uploaded by a
 * data mule alongside SOS frames. Wraps the shared zero-trust cascade
 * (`packages/shared` controlFrame.ts) — the wire layout, reject cascade and
 * domain-prefixed Ed25519 verification are owned by the shared contract so both
 * sides stay byte-identical. This module only adds base64 framing + the pinned
 * backend public key.
 *
 * Dedupe (signature replay) is intentionally NOT done here — the caller owns the
 * persistent replay guard (`controlReplayRepo`). A mule legitimately re-uploads
 * the same control frame; replay protection is an admission decision, not a
 * verification one.
 */
export async function decodeAndVerifyControlFrame(
  frameB64: string,
  opts: VerifyControlFrameOptions = { nowMs: Date.now(), backendPublicKeyHex: publicKeyHex },
): Promise<ControlFrameResult> {
  let raw: Buffer;
  try {
    raw = Buffer.from(frameB64, "base64");
  } catch {
    return { ok: false, reason: "invalid base64" };
  }
  return verifyControlFrame(Uint8Array.from(raw), opts);
}
