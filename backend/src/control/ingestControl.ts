import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { randomBytes } from "node:crypto";
import {
  buildControlFrame,
  controlSignBytes,
  canonicalEventBytes,
  computeEventId,
  computePayloadHash,
  deriveSosId,
  CONTROL_MAX_LIFETIME_SEC,
  CONTROL_TYPE_BACKEND_RESOLVE,
  CONTROL_TYPE_DELIVERY,
  CONTROL_TYPE_SELF_CANCEL,
  type CancelPayload,
  type ControlFrameResult,
  type LedgerEvent,
  type SelfCancelProof,
  type UnsignedLedgerEvent,
  type VerifiedControlFrame,
} from "@guacamaya/shared";
import { privateKey, publicKeyBytes, publicKeyHex } from "../crypto/keys.js";
import { ledgerRepo } from "../ledger/ledgerRepo.js";
import { controlReplayRepo } from "./replayRepo.js";
import { decodeAndVerifyControlFrame } from "../mesh/controlFrame.js";

/**
 * Control-frame ingress on the data-mule path (`POST /ingest`). A mule carries
 * verified control frames (delivery / backend-resolve / self-cancel) alongside
 * SOS frames. Every frame is re-verified zero-trust (never trust the mule), then
 * admitted once per signature via the persistent replay guard.
 *
 *   0x03 self-cancel     → emits SOS_CANCELLED (proofVersion 2; the frame's
 *                          domain-prefixed Ed25519 sig IS the victim proof)
 *   0x01 delivery        → ack only, no ledger mutation
 *   0x02 backend-resolve → ack only; the resolve it represents was already
 *                          emitted at issuance (quorum /resolve path)
 *
 * Minting of fresh control frames for the mule to carry back is separate
 * (`mintControlFrames`): a delivery ack for a freshly-seen active SOS, and a
 * backend-resolve stop order for any SOS whose reconciled ledger state is
 * terminal yet is still being re-uploaded (victim still advertising a stale
 * SOS). A self-cancel accepted this batch flips state to terminal, so a
 * co-uploaded SOS frame for the same victim yields a backend-resolve — the
 * backend endorses and propagates the stop beyond the victim.
 */

export interface SosRef {
  /** Victim pubkey hex (ChannelRecord.author minus the "device-" prefix). */
  pubkeyHex: string;
  /** sha256(victim pubkey)[0..3] hex — the control targetNodeId. */
  nodeIdHex: string;
  msgId: number;
  /** True iff this SOS frame was newly ingested (not a dedupe hit). */
  fresh: boolean;
  sosId: string;
}

export interface ControlIngestResult {
  accepted: number;
  duplicate: number;
  rejected: number;
  reasons: Record<string, number>;
}

export interface MintedControlFrame {
  type: number;
  frameB64: string;
}

function randomU16(): number {
  return randomBytes(2).readUInt16BE(0);
}

/**
 * Verify + replay-admit each control frame, emitting SOS_CANCELLED for accepted
 * self-cancels. Verification failures are counted (never persisted); replays are
 * deduped by signature. Ledger emission failures are logged but never break
 * ingest (the frame is already recorded as a replay entry).
 */
export async function processControlFrames(
  controlFrames: string[],
  nowMs = Date.now(),
): Promise<ControlIngestResult> {
  let accepted = 0;
  let duplicate = 0;
  let rejected = 0;
  const reasons: Record<string, number> = {};

  for (const frameB64 of controlFrames) {
    const result: ControlFrameResult = await decodeAndVerifyControlFrame(frameB64, {
      nowMs,
      backendPublicKeyHex: publicKeyHex,
    });
    if (!result.ok) {
      rejected++;
      reasons[result.reason] = (reasons[result.reason] ?? 0) + 1;
      continue;
    }

    const c = result.control;
    const admitted = await controlReplayRepo.admit(
      {
        signatureHex: c.signatureHex,
        frameType: c.type,
        issuerNodeIdHex: c.issuerNodeIdHex,
        ctrlMsgId: c.ctrlMsgId,
        expiresAtMs: c.expiresAt * 1000,
      },
      nowMs,
    );
    if (!admitted) {
      duplicate++;
      continue;
    }
    accepted++;

    if (c.type === CONTROL_TYPE_SELF_CANCEL) {
      await emitSelfCancelLedgerEvent(c, nowMs);
    }
  }

  return { accepted, duplicate, rejected, reasons };
}

/**
 * Derive SOS_CANCELLED from a verified 0x03 self-cancel frame and upsert it.
 * proofVersion 2: receiptCanonicalBytes = controlSignBytes(payload22) (UTF8
 * domain + 22-byte control payload), victimSignature = the frame's Ed25519
 * sig64 over those same bytes. verifyLedgerEvent's raw verify holds unchanged.
 * The backend co-signs the event envelope (eventSignature); issuerPublicKey is
 * the backend, victimPublicKey (in the proof) is the cancel authority.
 */
async function emitSelfCancelLedgerEvent(control: VerifiedControlFrame, nowMs: number): Promise<void> {
  const victimPubkey = hexToBytes(control.issuerPublicKeyHex);
  const sosId = deriveSosId(victimPubkey, control.targetMsgId);

  const previousEvents = await ledgerRepo.getEventsBySosId(sosId);
  const created = previousEvents.find((s) => s.event.type === "SOS_CREATED");
  const previousSosEventHash = created?.event.eventId ?? null;

  const cancelledAt = control.issuedAt * 1000; // frame carries unix seconds; ledger uses ms
  const payload: CancelPayload = {
    payloadVersion: 1,
    cancelledAt,
    reason: "self-cancel",
  };

  const proof: SelfCancelProof = {
    proofType: "self-cancel",
    proofVersion: 2,
    receiptCanonicalBytes: controlSignBytes(control.payload),
    victimSignature: hexToBytes(control.signatureHex),
    victimPublicKey: victimPubkey,
    backendReceiptId: bytesToHex(sha256(control.frame)),
  };

  const unsigned: UnsignedLedgerEvent = {
    version: 1,
    sosId,
    type: "SOS_CANCELLED",
    previousSosEventHash,
    issuedAt: nowMs,
    issuerPublicKey: publicKeyBytes,
    payload,
    payloadHash: computePayloadHash(payload),
    payloadVersion: 1,
    proof,
  };

  const eventId = computeEventId(unsigned);
  const eventSignature = await ed.signAsync(canonicalEventBytes(unsigned), privateKey);
  const event: LedgerEvent = { ...unsigned, eventId, eventSignature };
  try {
    await ledgerRepo.upsertEvent(event);
  } catch (err) {
    console.error("[ledger] self-cancel upsert failed", err);
  }
}

/**
 * Mint best-effort control frames for the mule to carry back into the mesh:
 *   - delivery (0x01)  for a freshly-ingested, still-active SOS
 *   - backend-resolve (0x02) for any SOS whose reconciled state is terminal
 *     (resolved/cancelled/expired) yet is still being re-uploaded — the victim
 *     is advertising a stale SOS and the backend orders it to stop.
 * Deduped per response by `${nodeIdHex}:${msgId}:${type}`. ctrlMsgId is CSPRNG.
 */
export async function mintControlFrames(
  sosRefs: SosRef[],
  nowMs = Date.now(),
): Promise<MintedControlFrame[]> {
  const nowSec = Math.floor(nowMs / 1000);
  const minted: MintedControlFrame[] = [];
  const seen = new Set<string>();

  for (const ref of sosRefs) {
    const state = await ledgerRepo.getSosState(ref.sosId);
    const terminal = state?.terminal === true;

    if (terminal) {
      const key = `${ref.nodeIdHex}:${ref.msgId}:${CONTROL_TYPE_BACKEND_RESOLVE}`;
      if (seen.has(key)) continue;
      seen.add(key);
      const frame = await buildControlFrame(
        {
          type: CONTROL_TYPE_BACKEND_RESOLVE,
          targetNodeId: hexToBytes(ref.nodeIdHex),
          targetMsgId: ref.msgId,
          issuedAt: nowSec,
          expiresAt: nowSec + CONTROL_MAX_LIFETIME_SEC[CONTROL_TYPE_BACKEND_RESOLVE],
          ctrlMsgId: randomU16(),
        },
        privateKey,
      );
      minted.push({ type: CONTROL_TYPE_BACKEND_RESOLVE, frameB64: Buffer.from(frame).toString("base64") });
      continue;
    }

    if (ref.fresh) {
      const key = `${ref.nodeIdHex}:${ref.msgId}:${CONTROL_TYPE_DELIVERY}`;
      if (seen.has(key)) continue;
      seen.add(key);
      const frame = await buildControlFrame(
        {
          type: CONTROL_TYPE_DELIVERY,
          targetNodeId: hexToBytes(ref.nodeIdHex),
          targetMsgId: ref.msgId,
          issuedAt: nowSec,
          expiresAt: nowSec + CONTROL_MAX_LIFETIME_SEC[CONTROL_TYPE_DELIVERY],
          ctrlMsgId: randomU16(),
        },
        privateKey,
      );
      minted.push({ type: CONTROL_TYPE_DELIVERY, frameB64: Buffer.from(frame).toString("base64") });
    }
  }

  return minted;
}
