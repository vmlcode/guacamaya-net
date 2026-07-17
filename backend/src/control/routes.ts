import type { FastifyInstance } from "fastify";
import * as ed from "@noble/ed25519";
import { hexToBytes } from "@noble/hashes/utils.js";
import {
  getRecordId,
  ReceiptReplayGuard,
  verifyControlReceipt,
  deriveSosId,
  computeEventId,
  computePayloadHash,
  canonicalEventBytes,
  hash,
  type BackendReceiptProof,
  type CancelPayload,
  type ChannelRecord,
  type ControlReceipt,
  type LedgerEvent,
  type ResolvePayload,
  type SelfCancelProof,
  type UnsignedLedgerEvent,
} from "@guacamaya/shared";
import { privateKey, publicKeyBytes, publicKeyHex } from "../crypto/keys.js";
import { channelsRepo } from "../db/channelsRepo.js";
import { ledgerRepo } from "../ledger/ledgerRepo.js";
import { broadcastRecord } from "../ws/server.js";

const replay = new ReceiptReplayGuard();

/**
 * NAN control ingress. Soft resolves are visual-only and need a valid rescuer
 * certificate; backend-resolve/self-cancel are the only receipts that may stop
 * the originating transmitter. The Android transport owns the actual stop.
 *
 * On a freshly-accepted receipt, derive the corresponding ledger event:
 *   - backend-resolve → SOS_RESOLVED (backend-receipt proof)
 *   - self-cancel     → SOS_CANCELLED (self-cancel proof; requires victimSignature)
 *   - soft-resolve / delivery → no ledger mutation
 */
export async function controlRoutes(fastify: FastifyInstance) {
  fastify.post<{ Body: ControlReceipt }>("/control/receipts", async (request, reply) => {
    const receipt = request.body;
    if (!receipt || !await verifyControlReceipt(receipt, publicKeyHex) || !replay.admit(receipt)) {
      return reply.code(400).send({ accepted: false, error: "invalid_or_replayed_receipt" });
    }
    const unsigned = {
      channel: "resuelto" as const,
      timestamp: receipt.issuedAt,
      ttl: 0,
      author: `device-${receipt.issuerPublicKey}`,
      verified: false,
      payload: { source: "control-receipt", receipt },
    };
    const record: ChannelRecord = { ...unsigned, id: getRecordId(unsigned) };
    const added = await channelsRepo.addRecord(record);
    if (added) {
      broadcastRecord(record);
      await emitControlLedgerEvent(receipt);
    }
    return { accepted: true, duplicate: !added, visualOnly: receipt.type === "soft-resolve" };
  });
}

/**
 * Derive SOS_RESOLVED (backend-resolve) or SOS_CANCELLED (self-cancel) from a
 * freshly-accepted control receipt and upsert it into the ledger. The receipt
 * authorizes the action; the ledger event is *additive* — reconcileState picks
 * the winner per compareAuthority if a competing event already exists for the
 * same sosId.
 *
 * sosId is recomputed from the origin SOS frame (looked up via targetSosId),
 * not from the receipt issuer: a rescuer-cancel chains under the victim's sosId,
 * not the rescuer's.
 */
async function emitControlLedgerEvent(receipt: ControlReceipt): Promise<void> {
  if (receipt.type !== "backend-resolve" && receipt.type !== "self-cancel") return;

  const targetRec = await channelsRepo.getById(receipt.targetSosId);
  if (!targetRec || !targetRec.author.startsWith("device-")) return;
  const messageId = (targetRec.payload as { msgId?: unknown }).msgId;
  if (typeof messageId !== "number" || !Number.isFinite(messageId)) return;

  const originPubkey = hexToBytes(targetRec.author.slice("device-".length));
  const sosId = deriveSosId(originPubkey, messageId);
  const previousEvents = await ledgerRepo.getEventsBySosId(sosId);
  const created = previousEvents.find((s) => s.event.type === "SOS_CREATED");
  const previousSosEventHash = created?.event.eventId ?? null;
  const now = Date.now();
  const issuerPublicKey = publicKeyBytes;

  let unsigned: UnsignedLedgerEvent;
  if (receipt.type === "backend-resolve") {
    const payload: ResolvePayload = {
      payloadVersion: 1,
      resolvedAt: receipt.issuedAt,
      quorumReachedAt: receipt.issuedAt,
      backendIssuedAt: now,
      witnesses: [],
    };
    const proof: BackendReceiptProof = {
      proofType: "backend-receipt",
      proofVersion: 1,
      backingReceiptId: getRecordId({
        channel: "resuelto",
        timestamp: receipt.issuedAt,
        ttl: 0,
        author: `device-${receipt.issuerPublicKey}`,
        verified: false,
        payload: { source: "control-receipt", receipt },
      }),
      receiptType: receipt.type,
      receiptIssuedAt: receipt.issuedAt,
    };
    unsigned = {
      version: 1, sosId, type: "SOS_RESOLVED", previousSosEventHash,
      issuedAt: now, issuerPublicKey, payload, payloadHash: computePayloadHash(payload),
      payloadVersion: 1, proof,
    };
  } else {
    // self-cancel — victimSignature is required. Without it, we cannot build a
    // verifiable self-cancel proof; skip ledger emission but keep the receipt
    // persisted as a ChannelRecord.
    if (!receipt.victimSignature || !receipt.victimPublicKey) return;
    if (receipt.victimPublicKey !== targetRec.author.slice("device-".length)) return;
    const payload: CancelPayload = {
      payloadVersion: 1,
      cancelledAt: receipt.issuedAt,
      reason: "self-cancel",
    };
    const receiptCanonicalBytes = hash("guacamaya.control-receipt.v1", {
      type: receipt.type,
      targetSosId: receipt.targetSosId,
      targetSosAuthor: receipt.targetSosAuthor,
      issuedAt: receipt.issuedAt,
      expiresAt: receipt.expiresAt,
      issuerPublicKey: receipt.issuerPublicKey,
      ...(receipt.certificate ? { certificate: receipt.certificate } : {}),
    });
    const proof: SelfCancelProof = {
      proofType: "self-cancel",
      proofVersion: 1,
      receiptCanonicalBytes,
      victimSignature: hexToBytes(receipt.victimSignature),
      victimPublicKey: hexToBytes(receipt.victimPublicKey),
      backendReceiptId: getRecordId({
        channel: "resuelto",
        timestamp: receipt.issuedAt,
        ttl: 0,
        author: `device-${receipt.issuerPublicKey}`,
        verified: false,
        payload: { source: "control-receipt", receipt },
      }),
    };
    unsigned = {
      version: 1, sosId, type: "SOS_CANCELLED", previousSosEventHash,
      issuedAt: now, issuerPublicKey, payload, payloadHash: computePayloadHash(payload),
      payloadVersion: 1, proof,
    };
  }

  const eventId = computeEventId(unsigned);
  const eventSignature = await ed.signAsync(canonicalEventBytes(unsigned), privateKey);
  const event: LedgerEvent = { ...unsigned, eventId, eventSignature };
  try {
    await ledgerRepo.upsertEvent(event);
  } catch (err) {
    console.error("[ledger] control receipt upsert failed", err);
  }
}
