import { beforeEach, describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import {
  buildControlFrame,
  crc16Ccitt,
  deriveSosId,
  computeEventId,
  computePayloadHash,
  verifyControlFrame,
  verifyLedgerEvent,
  SOS_CHANNEL,
  CONTROL_TYPE_BACKEND_RESOLVE,
  CONTROL_TYPE_DELIVERY,
  CONTROL_TYPE_SELF_CANCEL,
  type ChannelRecordProof,
  type LedgerEvent,
  type SosCreatedPayload,
  type UnsignedLedgerEvent,
} from "@guacamaya/shared";
import { processControlFrames, mintControlFrames, type SosRef } from "./ingestControl.js";
import { ledgerRepo } from "../ledger/ledgerRepo.js";
import { ledgerStore } from "../ledger/store.js";
import { controlReplayRepo } from "./replayRepo.js";
import { publicKeyBytes, publicKeyHex } from "../crypto/keys.js";

// ─── Fixtures ────────────────────────────────────────────────────────────────
// Victim key is deterministic (0x11 * 32) so its pubkey / node id are stable.
const VICTIM_PRIV = hexToBytes("11".repeat(32));
const OTHER_PRIV = hexToBytes("33".repeat(32)); // a second device, for binding tests

const NOW_MS = 1_800_000_500_000; // 2027-01-15-ish; fixed for deterministic time windows
const NOW_SEC = Math.floor(NOW_MS / 1000);
const SOS_TS_UNIX = 1_800_000_000; // SOS created ~500s before NOW
const MSG_ID = 0x1234;

let victimPub: Uint8Array;
let victimNodeId: Uint8Array; // sha256(victimPub)[0..3]
let victimNodeIdHex: string;

beforeEach(async () => {
  ledgerStore.reset();
  controlReplayRepo.reset();
  victimPub = await ed.getPublicKeyAsync(VICTIM_PRIV);
  victimNodeId = sha256(victimPub).subarray(0, 4);
  victimNodeIdHex = bytesToHex(victimNodeId);
});

// ─── SOS frame helpers (shared has no SOS builder; mirror Payload.kt) ─────────
// payload22 BE: latE7 i32@0, lonE7 i32@4, tsUnix u32@8, nodeId[12..15],
// flags@16, sosTypeCode@17, msgId u16@18, CRC16-CCITT@20 (over 0..19).
// SOS sig = Ed25519 over the bare payload22 (no domain) — distinct from control.
function buildSosPayload(
  nodeId: Uint8Array,
  msgId: number,
  tsUnix: number,
  sosTypeCode = 0x00,
): Uint8Array {
  const p = new Uint8Array(22);
  const dv = new DataView(p.buffer);
  dv.setInt32(0, 10_000_000, false); // lat = 1.0
  dv.setInt32(4, -80_000_000, false); // lon = -8.0
  dv.setUint32(8, tsUnix, false);
  p.set(nodeId, 12);
  p[16] = 0; // flags
  p[17] = sosTypeCode;
  dv.setUint16(18, msgId, false);
  dv.setUint16(20, crc16Ccitt(p, 0, 20), false);
  return p;
}

/** Seed a SOS_CREATED ledger event exactly as deriveAndUpsertSosCreated does. */
async function seedSosCreated(msgId = MSG_ID): Promise<{ sosId: string; eventId: string }> {
  const payload22 = buildSosPayload(victimNodeId, msgId, SOS_TS_UNIX);
  const sig = await ed.signAsync(payload22, VICTIM_PRIV);
  const recordId = bytesToHex(sha256(payload22));
  const sosId = deriveSosId(victimPub, msgId);

  const ledgerPayload: SosCreatedPayload = {
    payloadVersion: 1,
    backingRecordId: recordId,
    channel: SOS_CHANNEL,
    messageId: msgId,
    createdAt: SOS_TS_UNIX * 1000,
  };
  const proof: ChannelRecordProof = {
    proofType: "channel-record",
    proofVersion: 1,
    encoding: "ble-payload-v1",
    recordCanonicalBytes: payload22,
    recordSignature: sig,
    backingRecordId: recordId,
    messageId: msgId,
  };
  const unsigned: UnsignedLedgerEvent = {
    version: 1,
    sosId,
    type: "SOS_CREATED",
    previousSosEventHash: null,
    issuedAt: SOS_TS_UNIX * 1000,
    issuerPublicKey: victimPub,
    payload: ledgerPayload,
    payloadHash: computePayloadHash(ledgerPayload),
    payloadVersion: 1,
    proof,
  };
  const event: LedgerEvent = { ...unsigned, eventId: computeEventId(unsigned) };
  await ledgerRepo.upsertEvent(event);
  return { sosId, eventId: event.eventId };
}

/** Build a 0x03 self-cancel frame signed by `issuerPriv` targeting `targetNodeId`. */
async function buildSelfCancel(
  issuerPriv: Uint8Array,
  targetNodeId: Uint8Array,
  targetMsgId = MSG_ID,
  issuedAt = NOW_SEC,
  lifetimeSec = 3600,
  ctrlMsgId = 0xabcd,
): Promise<string> {
  const frame = await buildControlFrame(
    {
      type: CONTROL_TYPE_SELF_CANCEL,
      targetNodeId,
      targetMsgId,
      issuedAt,
      expiresAt: issuedAt + lifetimeSec,
      ctrlMsgId,
    },
    issuerPriv,
  );
  return Buffer.from(frame).toString("base64");
}

function ref(fresh: boolean, msgId = MSG_ID): SosRef {
  return {
    pubkeyHex: bytesToHex(victimPub),
    nodeIdHex: victimNodeIdHex,
    msgId,
    fresh,
    sosId: deriveSosId(victimPub, msgId),
  };
}

// ─── processControlFrames ────────────────────────────────────────────────────

describe("processControlFrames", () => {
  test("accepts a valid self-cancel and emits SOS_CANCELLED (terminal)", async () => {
    const { sosId, eventId: createdId } = await seedSosCreated();
    const frameB64 = await buildSelfCancel(VICTIM_PRIV, victimNodeId);

    const result = await processControlFrames([frameB64], NOW_MS);

    expect(result).toEqual({ accepted: 1, duplicate: 0, rejected: 0, reasons: {} });

    const state = await ledgerRepo.getSosState(sosId);
    expect(state?.type).toBe("cancelled");
    expect(state?.terminal).toBe(true);

    // The emitted SOS_CANCELLED is attached to the CREATED event and verifies.
    const events = await ledgerRepo.getEventsBySosId(sosId);
    const cancelled = events.find((s) => s.event.type === "SOS_CANCELLED");
    expect(cancelled).toBeDefined();
    expect(cancelled!.event.previousSosEventHash).toBe(createdId);
    const verify = await verifyLedgerEvent(cancelled!.event, publicKeyBytes);
    expect(verify).toEqual({ ok: true });
  });

  test("rejects an expired self-cancel", async () => {
    await seedSosCreated();
    const expired = await buildSelfCancel(
      VICTIM_PRIV,
      victimNodeId,
      MSG_ID,
      NOW_SEC - 7200,
      3600, // expiresAt = NOW - 3600 → in the past
    );

    const result = await processControlFrames([expired], NOW_MS);

    expect(result.accepted).toBe(0);
    expect(result.rejected).toBe(1);
    expect(result.reasons["control frame expired"]).toBe(1);
  });

  test("rejects a frame whose CRC does not validate", async () => {
    await seedSosCreated();
    const frame = await buildSelfCancel(VICTIM_PRIV, victimNodeId);
    // Flip a payload byte covered by the CRC (targetMsgId high byte @ off 5).
    const bytes = Uint8Array.from(Buffer.from(frame, "base64"));
    bytes[5] ^= 0xff;

    const result = await processControlFrames([Buffer.from(bytes).toString("base64")], NOW_MS);

    expect(result.accepted).toBe(0);
    expect(result.rejected).toBe(1);
    expect(result.reasons["crc mismatch"]).toBe(1);
  });

  test("rejects a self-cancel not signed by the target victim", async () => {
    await seedSosCreated();
    // Issuer = victim, but target points at a different device's node id.
    const otherPub = await ed.getPublicKeyAsync(OTHER_PRIV);
    const otherNodeId = sha256(otherPub).subarray(0, 4);
    const frame = await buildSelfCancel(VICTIM_PRIV, otherNodeId);

    const result = await processControlFrames([frame], NOW_MS);

    expect(result.accepted).toBe(0);
    expect(result.rejected).toBe(1);
    // Signature is valid (signed by the issuer over its own payload); the
    // binding check rejects it: issuer node id != target node id.
    expect(result.reasons["self-cancel issuer is not the target"]).toBe(1);
  });

  test("dedupes a replayed self-cancel by signature", async () => {
    await seedSosCreated();
    const frame = await buildSelfCancel(VICTIM_PRIV, victimNodeId);

    const first = await processControlFrames([frame], NOW_MS);
    const second = await processControlFrames([frame], NOW_MS);

    expect(first.accepted).toBe(1);
    expect(second.accepted).toBe(0);
    expect(second.duplicate).toBe(1);
  });
});

// ─── mintControlFrames ───────────────────────────────────────────────────────

describe("mintControlFrames", () => {
  test("mints a delivery ack for a fresh, still-active SOS", async () => {
    const { sosId } = await seedSosCreated();

    const minted = await mintControlFrames([ref(true)], NOW_MS);

    expect(minted).toHaveLength(1);
    expect(minted[0]!.type).toBe(CONTROL_TYPE_DELIVERY);

    const decoded = await verifyControlFrame(
      Uint8Array.from(Buffer.from(minted[0]!.frameB64, "base64")),
      { nowMs: NOW_MS, backendPublicKeyHex: publicKeyHex },
    );
    expect(decoded.ok).toBe(true);
    if (decoded.ok) {
      expect(decoded.control.type).toBe(CONTROL_TYPE_DELIVERY);
      expect(decoded.control.targetMsgId).toBe(MSG_ID);
      expect(decoded.control.targetNodeIdHex).toBe(victimNodeIdHex);
    }
    // Active SOS is unchanged.
    expect((await ledgerRepo.getSosState(sosId))?.type).toBe("active");
  });

  test("mints a backend-resolve stop order for a terminal (cancelled) SOS", async () => {
    const { sosId } = await seedSosCreated();
    await processControlFrames([await buildSelfCancel(VICTIM_PRIV, victimNodeId)], NOW_MS);
    expect((await ledgerRepo.getSosState(sosId))?.terminal).toBe(true);

    // Re-upload of a stale SOS (not fresh) whose state is now terminal.
    const minted = await mintControlFrames([ref(false)], NOW_MS);

    expect(minted).toHaveLength(1);
    expect(minted[0]!.type).toBe(CONTROL_TYPE_BACKEND_RESOLVE);

    const decoded = await verifyControlFrame(
      Uint8Array.from(Buffer.from(minted[0]!.frameB64, "base64")),
      { nowMs: NOW_MS, backendPublicKeyHex: publicKeyHex },
    );
    expect(decoded.ok).toBe(true);
    if (decoded.ok) expect(decoded.control.type).toBe(CONTROL_TYPE_BACKEND_RESOLVE);
  });

  test("mints nothing for a non-fresh, still-active re-upload", async () => {
    await seedSosCreated();
    const minted = await mintControlFrames([ref(false)], NOW_MS);
    expect(minted).toEqual([]);
  });

  test("dedupes minted frames per nodeId:msgId:type", async () => {
    await seedSosCreated();
    const minted = await mintControlFrames([ref(true), ref(true), ref(true)], NOW_MS);
    expect(minted).toHaveLength(1);
  });
});
