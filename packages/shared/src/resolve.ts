import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import type { ResolveEnvelope, ResolveWitness } from "./types.js";

/**
 * Resolve-flow crypto helpers. Pure, no backend deps.
 *
 * Canonical signing format (byte-stable):
 *   canonicalResolveBytes(envelope) =
 *     "guacamaya.resolve.v1\n" +
 *     envelope.targetSosId + "\n" +
 *     envelope.targetSosAuthor + "\n" +
 *     envelope.submittedAt + "\n" +
 *     (envelope.note ?? "") + "\n"
 *
 *   witnessMessageBytes(envelope, w) =
 *     canonicalResolveBytes(envelope) +
 *     w.deviceId + "\n" +
 *     w.lat.toFixed(7) + "\n" +
 *     w.lon.toFixed(7) + "\n" +
 *     w.ts + "\n" +
 *     w.imageHash + "\n" +
 *     (w.macObservationHashes?.join(",") ?? "") + "\n"
 *
 * Each witness signs sha256(witnessMessageBytes) with their own Ed25519 keypair.
 */

const RESOLVE_VERSION = "guacamaya.resolve.v1";

function strBytes(s: string): Uint8Array {
  return new TextEncoder().encode(s);
}

export function hashImage(bytes: Uint8Array): string {
  return bytesToHex(sha256(bytes));
}

export function canonicalResolveBytes(envelope: ResolveEnvelope): Uint8Array {
  const parts = [
    RESOLVE_VERSION,
    envelope.targetSosId,
    envelope.targetSosAuthor,
    String(envelope.submittedAt),
    envelope.note ?? "",
  ];
  return strBytes(parts.join("\n") + "\n");
}

export function witnessMessageBytes(
  envelope: ResolveEnvelope,
  witness: ResolveWitness
): Uint8Array {
  const base = canonicalResolveBytes(envelope);
  const tail = strBytes(
    [
      witness.deviceId,
      witness.lat.toFixed(7),
      witness.lon.toFixed(7),
      String(witness.ts),
      witness.imageHash,
      witness.macObservationHashes?.join(",") ?? "",
    ].join("\n") + "\n"
  );
  const out = new Uint8Array(base.length + tail.length);
  out.set(base, 0);
  out.set(tail, base.length);
  return out;
}

/**
 * Deterministic receipt id — `sha256(canonicalBytes || sortedWitnessDeviceIds)`.
 * Two submissions with the same target + witness set produce the same id.
 */
export function getResolveId(envelope: ResolveEnvelope): string {
  const base = canonicalResolveBytes(envelope);
  const sorted = [...envelope.witnesses]
    .map((w) => w.deviceId)
    .sort()
    .join(",");
  const tail = strBytes(sorted);
  const buf = new Uint8Array(base.length + tail.length);
  buf.set(base, 0);
  buf.set(tail, base.length);
  return bytesToHex(sha256(buf));
}

export async function signWitness(
  envelope: ResolveEnvelope,
  witness: ResolveWitness,
  privateKey: Uint8Array
): Promise<string> {
  const msg = sha256(witnessMessageBytes(envelope, witness));
  return bytesToHex(await ed.signAsync(msg, privateKey));
}

export async function verifyWitnessSignature(
  envelope: ResolveEnvelope,
  witness: ResolveWitness
): Promise<boolean> {
  try {
    const msg = sha256(witnessMessageBytes(envelope, witness));
    return await ed.verifyAsync(hexToBytes(witness.sig), msg, hexToBytes(witness.pubkey));
  } catch {
    return false;
  }
}

export interface ResolveVerifyResult {
  ok: boolean;
  validWitnesses: number;
}

export async function verifyResolveEnvelope(
  envelope: ResolveEnvelope
): Promise<ResolveVerifyResult> {
  let validWitnesses = 0;
  for (const w of envelope.witnesses) {
    if (await verifyWitnessSignature(envelope, w)) {
      validWitnesses++;
    }
  }
  return { ok: validWitnesses === envelope.witnesses.length, validWitnesses };
}
