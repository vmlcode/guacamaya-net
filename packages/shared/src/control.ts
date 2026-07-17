import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { canonicalJsonString } from "./canonical.js";
import type { CertificateRevocationList, ChannelRecord, ControlReceipt, MeshDigest, RescuerCertificate } from "./types.js";

export const SOFT_RESOLVE_WINDOW_MS = 10 * 60_000;
export const DELIVERY_RECEIPT_WINDOW_MS = 30 * 24 * 60 * 60_000;
/** Seven days tolerates an offline deployment; revocation is distributed separately. */
export const RESCUER_CERTIFICATE_MAX_MS = 7 * 24 * 60 * 60_000;

/**
 * Domain-separated hash over the shared canonical JSON form (canonical.ts).
 * Byte-identical to the historical local serializer for every valid receipt/
 * certificate (sorted keys, JSON scalars); the shared one additionally rejects
 * undefined/non-finite instead of emitting invalid JSON.
 */
export function hash(domain: string, value: unknown): Uint8Array {
  return sha256(new TextEncoder().encode(`${domain}\n${canonicalJsonString(value)}`));
}

export async function issueRescuerCertificate(
  rescuerPublicKey: string, expiresAt: number, backendPrivateKey: Uint8Array, now = Date.now(),
): Promise<RescuerCertificate> {
  if (expiresAt <= now || expiresAt - now > RESCUER_CERTIFICATE_MAX_MS) throw new Error("invalid certificate lifetime");
  const unsigned = { rescuerPublicKey, issuedAt: now, expiresAt };
  return { ...unsigned, backendSignature: bytesToHex(await ed.signAsync(hash("guacamaya.rescuer-cert.v1", unsigned), backendPrivateKey)) };
}

export async function verifyRescuerCertificate(cert: RescuerCertificate, backendPublicKey: string, at = Date.now()): Promise<boolean> {
  if (cert.issuedAt > at || cert.expiresAt < at || cert.expiresAt <= cert.issuedAt || cert.expiresAt - cert.issuedAt > RESCUER_CERTIFICATE_MAX_MS) return false;
  try { return await ed.verifyAsync(hexToBytes(cert.backendSignature), hash("guacamaya.rescuer-cert.v1", { rescuerPublicKey: cert.rescuerPublicKey, issuedAt: cert.issuedAt, expiresAt: cert.expiresAt }), hexToBytes(backendPublicKey)); } catch { return false; }
}

const crlUnsigned = (crl: Omit<CertificateRevocationList, "backendSignature">) => ({
  version: crl.version, sequence: crl.sequence, issuedAt: crl.issuedAt,
  nextUpdate: crl.nextUpdate, revokedPublicKeys: [...crl.revokedPublicKeys].sort(),
});

export async function issueCertificateRevocationList(revokedPublicKeys: string[], sequence: number, nextUpdate: number, backendPrivateKey: Uint8Array, now = Date.now()): Promise<CertificateRevocationList> {
  if (!Number.isSafeInteger(sequence) || sequence < 0 || nextUpdate <= now) throw new Error("invalid CRL metadata");
  const keys = [...new Set(revokedPublicKeys)];
  if (keys.some((key) => !/^[0-9a-f]{64}$/.test(key))) throw new Error("invalid revoked public key");
  const unsigned = crlUnsigned({ version: 1, sequence, issuedAt: now, nextUpdate, revokedPublicKeys: keys });
  return { ...unsigned, backendSignature: bytesToHex(await ed.signAsync(hash("guacamaya.rescuer-crl.v1", unsigned), backendPrivateKey)) };
}

export async function verifyCertificateRevocationList(crl: CertificateRevocationList, backendPublicKey: string, at = Date.now()): Promise<boolean> {
  if (crl.version !== 1 || !Number.isSafeInteger(crl.sequence) || crl.sequence < 0 || crl.nextUpdate < at || crl.issuedAt > at) return false;
  if (crl.revokedPublicKeys.some((key) => !/^[0-9a-f]{64}$/.test(key))) return false;
  try { return await ed.verifyAsync(hexToBytes(crl.backendSignature), hash("guacamaya.rescuer-crl.v1", crlUnsigned(crl)), hexToBytes(backendPublicKey)); } catch { return false; }
}

export function isCertificateRevoked(cert: RescuerCertificate, crl: CertificateRevocationList): boolean {
  return crl.revokedPublicKeys.includes(cert.rescuerPublicKey);
}

export async function signControlReceipt(unsigned: Omit<ControlReceipt, "signature">, privateKey: Uint8Array): Promise<ControlReceipt> {
  return { ...unsigned, signature: bytesToHex(await ed.signAsync(hash("guacamaya.control-receipt.v1", unsigned), privateKey)) };
}

export async function verifyControlReceipt(receipt: ControlReceipt, backendPublicKey: string, now = Date.now()): Promise<boolean> {
  if (receipt.expiresAt < now || receipt.expiresAt <= receipt.issuedAt) return false;
  const unsigned = { type: receipt.type, targetSosId: receipt.targetSosId, targetSosAuthor: receipt.targetSosAuthor, issuedAt: receipt.issuedAt, expiresAt: receipt.expiresAt, issuerPublicKey: receipt.issuerPublicKey, ...(receipt.certificate ? { certificate: receipt.certificate } : {}) };
  try {
    if (!await ed.verifyAsync(hexToBytes(receipt.signature), hash("guacamaya.control-receipt.v1", unsigned), hexToBytes(receipt.issuerPublicKey))) return false;
    // The certificate authorizes the act at signing time. A mule-delayed receipt
    // does not become forged merely because the certificate expired afterwards.
    if (receipt.type === "soft-resolve") return !!receipt.certificate && receipt.certificate.rescuerPublicKey === receipt.issuerPublicKey && await verifyRescuerCertificate(receipt.certificate, backendPublicKey, receipt.issuedAt);
    return receipt.type === "self-cancel" ? receipt.targetSosAuthor === `device-${receipt.issuerPublicKey}` : receipt.issuerPublicKey === backendPublicKey;
  } catch { return false; }
}

export function receiptStopsVictimBroadcast(receipt: ControlReceipt): boolean { return receipt.type === "backend-resolve" || receipt.type === "self-cancel"; }

/** Bounded replay guard. Keep this state in the mobile LRU store until expiresAt. */
export class ReceiptReplayGuard {
  private readonly seen = new Map<string, number>();
  admit(receipt: ControlReceipt, now = Date.now()): boolean {
    for (const [key, expiry] of this.seen) if (expiry < now) this.seen.delete(key);
    if (this.seen.has(receipt.signature) || receipt.expiresAt < now) return false;
    this.seen.set(receipt.signature, receipt.expiresAt); return true;
  }
}

export function buildMeshDigest(records: Iterable<ChannelRecord>, maxIds = 64): MeshDigest { return { ids: [...records].map((r) => r.id).sort().slice(-maxIds), generatedAt: Date.now() }; }
export function missingFromDigest(records: Iterable<ChannelRecord>, digest: MeshDigest): ChannelRecord[] { const known = new Set(digest.ids); return [...records].filter((r) => !known.has(r.id)); }

/** Detects a fork (same author + prevHash) or a gap in a per-author hash chain. */
export function inspectAuthorChain(records: Iterable<ChannelRecord>): { forks: string[]; gaps: string[] } {
  const byAuthor = new Map<string, ChannelRecord[]>(); for (const r of records) byAuthor.set(r.author, [...(byAuthor.get(r.author) ?? []), r]);
  const forks: string[] = [], gaps: string[] = [];
  for (const [author, rs] of byAuthor) { const ids = new Set(rs.map((r) => r.id)); const prev = new Set<string>(); for (const r of rs) { if (r.prevHash && !ids.has(r.prevHash)) gaps.push(`${author}:${r.id}`); if (r.prevHash && !prev.add(r.prevHash)) forks.push(`${author}:${r.prevHash}`); } }
  return { forks, gaps };
}
