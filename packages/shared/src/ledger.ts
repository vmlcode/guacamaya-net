import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import {
  canonicalJsonBytes,
  equalBytes,
  EVENT_ID_RE,
  SOS_ID_RE,
  hashCanonical,
  type EventId,
  type SosId,
} from "./canonical.js";
import type { ReceiptType, ResolveWitness } from "./types.js";

// ─── Domain constants ────────────────────────────────────────────────────────

export const SOS_CHANNEL = "solicito-ayuda" as const;

export type LedgerEventType =
  | "SOS_CREATED"
  | "SOS_RESOLVED"
  | "SOS_CANCELLED"
  | "SOS_EXPIRED"
  | "KEY_REVOKED";

// ─── Payloads ────────────────────────────────────────────────────────────────

export interface SosCreatedPayload {
  payloadVersion: 1;
  backingRecordId: string;
  channel: typeof SOS_CHANNEL;
  messageId: number;
  createdAt: number;
}

export interface ResolvePayload {
  payloadVersion: 1;
  resolvedAt: number;
  quorumReachedAt: number;
  backendIssuedAt: number;
  witnesses: ResolveWitness[];
}

export interface CancelPayload {
  payloadVersion: 1;
  cancelledAt: number;
  reason: string;
}

export interface ExpirePayload {
  payloadVersion: 1;
  expiredAt: number;
  reason: "ttl" | "manual";
}

export interface KeyRevocationPayload {
  payloadVersion: 1;
  revokedPublicKey: Uint8Array;
  reason: string;
}

export type LedgerEventPayload =
  | SosCreatedPayload
  | ResolvePayload
  | CancelPayload
  | ExpirePayload
  | KeyRevocationPayload;

// ─── Proofs ──────────────────────────────────────────────────────────────────

export interface ChannelRecordProof {
  proofType: "channel-record";
  proofVersion: 1;
  encoding: "ble-payload-v1" | "channel-record-canonical-v1";
  recordCanonicalBytes: Uint8Array;
  recordSignature: Uint8Array;
  backingRecordId: string;
  messageId: number;
}

export interface BackendReceiptProof {
  proofType: "backend-receipt";
  proofVersion: 1;
  backingReceiptId: string;
  receiptType: ReceiptType;
  receiptIssuedAt: number;
}

export interface ResolveQuorumProof {
  proofType: "resolve-quorum";
  proofVersion: 1;
  resolveReceiptId: string;
  witnessEnvelopeHash: string;
  quorumCount: number;
}

export interface SelfCancelProof {
  proofType: "self-cancel";
  /**
   * 1 = JSON-receipt digest: receiptCanonicalBytes is the 32-byte
   *     hash("guacamaya.control-receipt.v1", {...receipt}) produced by the
   *     NAN control-receipt path (control.ts). Used when the cancel arrives as
   *     a JSON ControlReceipt.
   * 2 = control-frame canonical bytes: receiptCanonicalBytes is
   *     controlSignBytes(payload22) — UTF8("guacamaya.control-frame.v1\n") ||
   *     22-byte control payload — from a verified 0x03 self-cancel mesh frame
   *     uploaded by a data mule. victimSignature is the frame's Ed25519 sig64
   *     over those same bytes, so verifyLedgerEvent's raw
   *     ed.verifyAsync(victimSignature, receiptCanonicalBytes, victimPublicKey)
   *     holds unchanged for both versions.
   */
  proofVersion: 1 | 2;
  receiptCanonicalBytes: Uint8Array;
  victimSignature: Uint8Array;
  victimPublicKey: Uint8Array;
  backendReceiptId: string;
}

export interface BackendTtlProof {
  proofType: "backend-ttl";
  proofVersion: 1;
  reason: string;
}

export interface KeyRevocationProof {
  proofType: "key-revocation";
  proofVersion: 1;
  revokedPublicKey: Uint8Array;
  crlEntryHash: string;
}

export type LedgerEventProof =
  | ChannelRecordProof
  | BackendReceiptProof
  | ResolveQuorumProof
  | SelfCancelProof
  | BackendTtlProof
  | KeyRevocationProof;

// ─── Event ───────────────────────────────────────────────────────────────────

export interface UnsignedLedgerEvent {
  version: 1;
  sosId: SosId;
  type: LedgerEventType;
  previousSosEventHash: EventId | null;
  issuedAt: number;
  issuerPublicKey: Uint8Array;
  payload: LedgerEventPayload;
  payloadHash: Uint8Array;
  payloadVersion: 1;
  proof: LedgerEventProof;
}

export interface LedgerEvent extends UnsignedLedgerEvent {
  eventId: EventId;
  /** 64 B Ed25519 over canonicalEventBytes by backend. Undefined for SOS_CREATED. */
  eventSignature?: Uint8Array;
}

// ─── Canonical event bytes ───────────────────────────────────────────────────

export function canonicalEventBytes(e: UnsignedLedgerEvent): Uint8Array {
  return new TextEncoder().encode(
    "guacamaya.ledger.v1\n" +
      `${e.version}\n` +
      `${e.sosId}\n` +
      `${e.type}\n` +
      `${e.previousSosEventHash ?? ""}\n` +
      `${e.issuedAt}\n` +
      `${bytesToHex(e.issuerPublicKey)}\n` +
      `${bytesToHex(e.payloadHash)}\n`,
  );
}

export function computePayloadHash(payload: LedgerEventPayload): Uint8Array {
  return hashCanonical(payload);
}

export function computeEventId(e: UnsignedLedgerEvent): EventId {
  return bytesToHex(sha256(canonicalEventBytes(e)));
}

// ─── sosId derivation ────────────────────────────────────────────────────────

export function deriveSosId(authorPublicKey: Uint8Array, messageId: number): SosId {
  const sep = new TextEncoder().encode("guacamaya:sos:v1");
  const mid = new Uint8Array(4);
  new DataView(mid.buffer).setUint32(0, messageId, false);
  const concat = new Uint8Array(sep.length + authorPublicKey.length + mid.length);
  concat.set(sep, 0);
  concat.set(authorPublicKey, sep.length);
  concat.set(mid, sep.length + authorPublicKey.length);
  return bytesToHex(sha256(concat));
}

// ─── Verification ────────────────────────────────────────────────────────────

export type LedgerRejectReason =
  | "INVALID_EVENT_ID"
  | "INVALID_SOS_ID"
  | "INVALID_PREVIOUS_HASH"
  | "PAYLOAD_HASH_MISMATCH"
  | "INVALID_PROOF"
  | "INVALID_SIGNATURE"
  | "MISSING_SOS_CREATED"
  | "DUPLICATE_EVENT"
  | "ILLEGAL_TRANSITION"
  | "UNAUTHORIZED"
  | "CHAIN_ORPHAN";

export type VerifyLedgerResult = { ok: true } | { ok: false; reason: LedgerRejectReason };

export async function verifyLedgerEvent(
  event: LedgerEvent,
  backendPublicKey: Uint8Array,
): Promise<VerifyLedgerResult> {
  if (!EVENT_ID_RE.test(event.eventId)) return { ok: false, reason: "INVALID_EVENT_ID" };
  if (!SOS_ID_RE.test(event.sosId)) return { ok: false, reason: "INVALID_SOS_ID" };
  if (
    event.previousSosEventHash !== null &&
    !EVENT_ID_RE.test(event.previousSosEventHash)
  ) {
    return { ok: false, reason: "INVALID_PREVIOUS_HASH" };
  }

  const expectedEventId = computeEventId(event);
  if (expectedEventId !== event.eventId) return { ok: false, reason: "INVALID_EVENT_ID" };

  const expectedPayloadHash = computePayloadHash(event.payload);
  if (!equalBytes(expectedPayloadHash, event.payloadHash)) {
    return { ok: false, reason: "PAYLOAD_HASH_MISMATCH" };
  }

  const proof = event.proof;
  switch (proof.proofType) {
    case "channel-record": {
      if (event.eventSignature) return { ok: false, reason: "INVALID_PROOF" };
      if (event.type !== "SOS_CREATED") return { ok: false, reason: "INVALID_PROOF" };

      const expectedRecordId = bytesToHex(sha256(proof.recordCanonicalBytes));
      if (expectedRecordId !== proof.backingRecordId) {
        return { ok: false, reason: "INVALID_PROOF" };
      }

      let recordSigValid = false;
      try {
        recordSigValid = await ed.verifyAsync(
          proof.recordSignature,
          proof.recordCanonicalBytes,
          event.issuerPublicKey,
        );
      } catch {
        recordSigValid = false;
      }
      if (!recordSigValid) return { ok: false, reason: "INVALID_SIGNATURE" };

      const createdPayload = event.payload as SosCreatedPayload;
      if (proof.messageId !== createdPayload.messageId) {
        return { ok: false, reason: "INVALID_PROOF" };
      }
      const derivedSosId = deriveSosId(event.issuerPublicKey, proof.messageId);
      if (derivedSosId !== event.sosId) return { ok: false, reason: "INVALID_PROOF" };
      return { ok: true };
    }
    case "backend-receipt":
    case "resolve-quorum":
    case "self-cancel":
    case "backend-ttl":
    case "key-revocation": {
      if (!event.eventSignature) return { ok: false, reason: "INVALID_SIGNATURE" };
      let backendSigValid = false;
      try {
        backendSigValid = await ed.verifyAsync(
          event.eventSignature,
          canonicalEventBytes(event),
          backendPublicKey,
        );
      } catch {
        backendSigValid = false;
      }
      if (!backendSigValid) return { ok: false, reason: "INVALID_SIGNATURE" };

      if (proof.proofType === "resolve-quorum") {
        const payload = event.payload as ResolvePayload;
        const expectedWitnessHash = bytesToHex(hashCanonical(payload.witnesses));
        if (expectedWitnessHash !== proof.witnessEnvelopeHash) {
          return { ok: false, reason: "INVALID_PROOF" };
        }
        if (proof.quorumCount !== payload.witnesses.length) {
          return { ok: false, reason: "INVALID_PROOF" };
        }
      }

      if (proof.proofType === "self-cancel") {
        let victimSigValid = false;
        try {
          victimSigValid = await ed.verifyAsync(
            proof.victimSignature,
            proof.receiptCanonicalBytes,
            proof.victimPublicKey,
          );
        } catch {
          victimSigValid = false;
        }
        if (!victimSigValid) return { ok: false, reason: "INVALID_SIGNATURE" };
      }
      return { ok: true };
    }
  }
}

// ─── State machine ───────────────────────────────────────────────────────────

export interface SosState {
  sosId: SosId;
  type: "active" | "resolved" | "cancelled" | "expired";
  terminal: boolean;
  lastEventId: EventId;
  lastEventAt: number;
  witnessCount: number;
  retentionUntil: number | null;
}

export type ApplyEventResult =
  | { accepted: true; state: SosState }
  | { accepted: false; reason: LedgerRejectReason };

export interface ApplyContext {
  /** SOS_CREATED event for this sosId if known. Required to authorize self-cancel. */
  createdEvent: LedgerEvent | null;
}

export function applyEvent(
  current: SosState | null,
  event: LedgerEvent,
  context: ApplyContext,
): ApplyEventResult {
  if (event.type === "SOS_CREATED") {
    if (current !== null) return { accepted: false, reason: "DUPLICATE_EVENT" };
    return {
      accepted: true,
      state: {
        sosId: event.sosId,
        type: "active",
        terminal: false,
        lastEventId: event.eventId,
        lastEventAt: event.issuedAt,
        witnessCount: 0,
        retentionUntil: null,
      },
    };
  }

  if (current === null) return { accepted: false, reason: "MISSING_SOS_CREATED" };
  if (current.terminal) return { accepted: false, reason: "ILLEGAL_TRANSITION" };

  // Self-cancel must come from the original SOS author.
  if (event.type === "SOS_CANCELLED" && event.proof.proofType === "self-cancel") {
    if (!context.createdEvent) return { accepted: false, reason: "MISSING_SOS_CREATED" };
    if (!equalBytes(event.proof.victimPublicKey, context.createdEvent.issuerPublicKey)) {
      return { accepted: false, reason: "UNAUTHORIZED" };
    }
  }

  switch (event.type) {
    case "SOS_RESOLVED": {
      const witnesses = (event.payload as ResolvePayload).witnesses;
      return {
        accepted: true,
        state: {
          ...current,
          type: "resolved",
          terminal: true,
          lastEventId: event.eventId,
          lastEventAt: event.issuedAt,
          witnessCount: witnesses?.length ?? current.witnessCount,
        },
      };
    }
    case "SOS_CANCELLED":
      return {
        accepted: true,
        state: {
          ...current,
          type: "cancelled",
          terminal: true,
          lastEventId: event.eventId,
          lastEventAt: event.issuedAt,
        },
      };
    case "SOS_EXPIRED":
      return {
        accepted: true,
        state: {
          ...current,
          type: "expired",
          terminal: true,
          lastEventId: event.eventId,
          lastEventAt: event.issuedAt,
        },
      };
    case "KEY_REVOKED":
      // Informational only; does not mutate SOS semantic state.
      return {
        accepted: true,
        state: {
          ...current,
          lastEventId: event.eventId,
          lastEventAt: event.issuedAt,
        },
      };
    default:
      return { accepted: false, reason: "ILLEGAL_TRANSITION" };
  }
}

// ─── Fork resolution ─────────────────────────────────────────────────────────

export enum AuthorizationRank {
  SELF_CANCEL = 100,
  CERTIFIED_RESCUER = 200,
  WITNESS_QUORUM = 300,
  BACKEND_ADMIN = 400,
}

export function rankAuthorization(e: LedgerEvent): number {
  switch (e.proof.proofType) {
    case "self-cancel":
      return AuthorizationRank.SELF_CANCEL;
    case "backend-receipt":
      return AuthorizationRank.WITNESS_QUORUM;
    case "resolve-quorum":
      return AuthorizationRank.WITNESS_QUORUM;
    case "backend-ttl":
      return AuthorizationRank.BACKEND_ADMIN;
    case "key-revocation":
      return AuthorizationRank.BACKEND_ADMIN;
    case "channel-record":
      // CREATED never participates in fork resolution against a sibling.
      return AuthorizationRank.SELF_CANCEL;
  }
}

function quorumCount(e: LedgerEvent): number {
  const p = e.proof as { quorumCount?: number };
  return p.quorumCount ?? 0;
}

/** Returns negative if `a` outranks `b`, positive if `b` outranks `a`, 0 if tied. */
export function compareAuthority(a: LedgerEvent, b: LedgerEvent): number {
  const rankDelta = rankAuthorization(b) - rankAuthorization(a);
  if (rankDelta !== 0) return rankDelta;
  const quorumDelta = quorumCount(b) - quorumCount(a);
  if (quorumDelta !== 0) return quorumDelta;
  return a.eventId.localeCompare(b.eventId);
}

// ─── Chain reconstruction ────────────────────────────────────────────────────

export interface ReconcileResult {
  state: SosState | null;
  attached: EventId[];
  conflicts: EventId[];
  orphans: EventId[];
}

/**
 * Rebuilds the chain for a sosId by walking `previousSosEventHash` from
 * SOS_CREATED. At each fork `compareAuthority` picks a winner; losers are
 * marked conflict. Events whose parent is missing entirely are orphans.
 */
export function reconcileState(events: LedgerEvent[]): ReconcileResult {
  const byId = new Map<EventId, LedgerEvent>();
  for (const e of events) byId.set(e.eventId, e);

  const created = events.find((e) => e.type === "SOS_CREATED") ?? null;
  const orphans: EventId[] = [];
  const conflicts: EventId[] = [];
  const attached: EventId[] = [];

  if (!created) {
    for (const e of events) orphans.push(e.eventId);
    return { state: null, attached, conflicts, orphans };
  }
  attached.push(created.eventId);

  // Walk children of the tip in chain order. A tip's children compete via
  // compareAuthority; the winner extends the attached chain, losers are
  // marked conflict. Loser subtrees are also conflict (transitively).
  let tip: LedgerEvent = created;
  const childrenByParent = new Map<EventId, LedgerEvent[]>();
  for (const e of events) {
    if (e.type === "SOS_CREATED") continue;
    if (e.previousSosEventHash === null) {
      orphans.push(e.eventId);
      continue;
    }
    if (!byId.has(e.previousSosEventHash)) {
      orphans.push(e.eventId);
      continue;
    }
    const siblings = childrenByParent.get(e.previousSosEventHash) ?? [];
    siblings.push(e);
    childrenByParent.set(e.previousSosEventHash, siblings);
  }

  let state = applyEvent(null, created, { createdEvent: created });
  if (!state.accepted) {
    return { state: null, attached, conflicts, orphans };
  }
  let current = state.state;

  // Iterate the attached chain forward.
  // eslint-disable-next-line no-constant-condition
  while (true) {
    const siblings = childrenByParent.get(tip.eventId) ?? [];
    if (siblings.length === 0) break;
    // Pick winner
    const sorted = [...siblings].sort(compareAuthority);
    const winner = sorted[0]!;
    for (let i = 1; i < sorted.length; i++) conflicts.push(sorted[i]!.eventId);

    // Mark loser subtrees as conflicts (transitive).
    const stack = sorted.slice(1);
    while (stack.length) {
      const loser = stack.pop()!;
      const loserChildren = childrenByParent.get(loser.eventId) ?? [];
      for (const c of loserChildren) {
        conflicts.push(c.eventId);
        stack.push(c);
      }
    }

    const next = applyEvent(current, winner, { createdEvent: created });
    if (!next.accepted) break;
    current = next.state;
    attached.push(winner.eventId);
    tip = winner;
  }

  return { state: current, attached, conflicts, orphans };
}

// ─── LedgerCursor ────────────────────────────────────────────────────────────

export interface LedgerCursor {
  receivedAt: number;
  eventId: EventId;
}
