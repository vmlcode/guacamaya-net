export type ChannelId =
  | "alertas"
  | "refugios"
  | "ayuda-medica"
  | "estoy-bien"
  | "solicito-ayuda"
  | (string & {});

export interface ChannelRecord {
  id: string;        // SHA-256 del contenido canónico → clave de dedupe
  channel: ChannelId;
  timestamp: number; // unix ms — momento del evento
  ttl: number;       // saltos mesh restantes; 0 = no reenviar
  author: string;    // "backend" | "device-<pubkey-hex>"
  verified: boolean; // true = firmado y verificado por el backend
  payload: unknown;
  sig?: string;      // firma Ed25519 hex (solo registros oficiales)
  /**
   * Per-author chain link: truncated hex (8-16 B) of the previous record by the
   * same author. Internal concept name `previousAuthorRecordHash`; wire field
   * stays `prevHash`. Distinct from `LedgerEvent.previousSosEventHash` (per-sosId
   * chain). Carried on NAN/control records, not in BLE v1.
   */
  prevHash?: string;
}

export type ReceiptType = "delivery" | "soft-resolve" | "backend-resolve" | "self-cancel";

/** Certificado compacto que autoriza a un rescatista a emitir soft-resolves por NAN. */
export interface RescuerCertificate {
  rescuerPublicKey: string;
  issuedAt: number;
  expiresAt: number;
  backendSignature: string;
}

export interface CertificateRevocationList {
  version: 1;
  sequence: number;
  issuedAt: number;
  nextUpdate: number;
  revokedPublicKeys: string[];
  backendSignature: string;
}

/** Mensaje de control firmado; se deduplica por firma y vence explícitamente. */
export interface ControlReceipt {
  type: ReceiptType;
  targetSosId: string;
  targetSosAuthor: string;
  issuedAt: number;
  expiresAt: number;
  issuerPublicKey: string;
  certificate?: RescuerCertificate;
  signature: string;
  /**
   * For `self-cancel` receipts only: the victim's Ed25519 signature over the
   * canonical receipt bytes (same object, minus `signature` and these victim
   * fields). Required for the ledger SOS_CANCELLED self-cancel proof.
   * Absent for all other receipt types.
   */
  victimSignature?: string;   // 64-byte Ed25519 hex
  victimPublicKey?: string;   // 32-byte hex; must equal the SOS originator's pubkey
}

export interface MeshDigest {
  ids: string[];
  generatedAt: number;
}

export type RecordMap = Map<string, ChannelRecord>;

export interface LocationPoint {
  id: string;         // SHA-256 of canonical content → dedup key
  deviceId: string;   // pseudonymous id of the origin device (not the mule)
  lat: number;        // latitude  [-90, 90]
  lon: number;        // longitude [-180, 180]
  timestamp: number;  // unix ms of the GPS fix
  accuracy?: number;  // precision in meters (optional)
}

// ─── Resolve flow ─────────────────────────────────────────────────────────────
// A finder (different device than the SOS originator) submits a co-signed
// resolve envelope to disarm an active SOS. Quorum M-of-N replaces victim
// confirmation — works when the victim's device is offline / dead / crushed.

export type ResolveStatus = "pending" | "cleared" | "disputed" | "rejected";

export interface ResolveEvidenceRef {
  imageHash: string;     // sha256(imageBytes) hex
  storageKey: string;    // path in bucket (prod) or fs (dev)
  uploadToken: string;   // HMAC binding the witness envelope to a short evidence window
}

export interface ResolveWitness {
  deviceId: string;      // "device-<pubkey-hex>" — must differ from targetSosAuthor
  pubkey: string;        // 32-byte Ed25519 hex
  lat: number;           // [-90, 90] witness position at resolve time
  lon: number;           // [-180, 180]
  ts: number;            // unix ms witness observation time
  imageHash: string;     // sha256 of uploaded evidence image
  uploadToken?: string;  // HMAC binding this imageHash to a recent /resolve/evidence upload
  macObservationHashes?: string[];  // soft forensic signal only — Android rotates MAC
  sig: string;           // 64-byte Ed25519 hex over canonical witness bytes
}

export interface ResolveEnvelope {
  targetSosId: string;          // ChannelRecord.id of the original SOS
  targetSosAuthor: string;      // "device-<pubkey-hex>" of the originator
  witnesses: ResolveWitness[];  // length 1..RESOLVE_QUORUM_TOTAL
  submittedAt: number;          // unix ms
  note?: string;                // free text, ≤ 512 chars
}

export interface ResolveReceipt {
  id: string;                   // getResolveId(envelope) — idempotent key
  targetSosId: string;
  targetSosAuthor: string;
  status: ResolveStatus;
  quorumNeeded: number;
  quorumSeen: number;
  witnessDeviceIds: string[];   // accepted witnesses (in arrival order)
  createdAt: number;
  cooldownEndsAt?: number;      // set when status flips to pending-clear
  disputedReason?: string;
}
