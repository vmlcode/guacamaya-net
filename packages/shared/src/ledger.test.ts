import { describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { bytesToHex } from "@noble/hashes/utils.js";
import { sha256 } from "@noble/hashes/sha2.js";
import {
  canonicalJsonBytes,
  canonicalJsonString,
  EVENT_ID_RE,
  SOS_ID_RE,
} from "./canonical.js";
import {
  computeEventId,
  computePayloadHash,
  canonicalEventBytes,
  deriveSosId,
  applyEvent,
  compareAuthority,
  reconcileState,
  verifyLedgerEvent,
  SOS_CHANNEL,
  type LedgerEvent,
  type SosCreatedPayload,
  type ChannelRecordProof,
  type ResolvePayload,
  type ResolveQuorumProof,
  type ResolveWitness,
  type UnsignedLedgerEvent,
} from "./ledger.js";

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Build a 22-byte BLE-style payload with `messageId` at offset 18 (BE u16). */
function makeBlePayload(messageId: number, tsUnixSec = 1_800_000_000): Uint8Array {
  const p = new Uint8Array(22);
  const dv = new DataView(p.buffer);
  // lat/lon E7 zeros; ts at its offset; messageId at 18.
  dv.setUint32(8, tsUnixSec, false);
  dv.setUint16(18, messageId, false);
  return p;
}

async function buildSosCreatedEvent(
  authorPrivate: Uint8Array,
  authorPublic: Uint8Array,
  messageId: number,
  issuedAt = 1_800_000_000_000,
  createdAt = 1_800_000_000_000,
): Promise<LedgerEvent> {
  const payloadBytes = makeBlePayload(messageId);
  const recordSignature = await ed.signAsync(payloadBytes, authorPrivate);
  const backingRecordId = bytesToHex(sha256(payloadBytes));

  const payload: SosCreatedPayload = {
    payloadVersion: 1,
    backingRecordId,
    channel: SOS_CHANNEL,
    messageId,
    createdAt,
  };
  const payloadHash = computePayloadHash(payload);

  const proof: ChannelRecordProof = {
    proofType: "channel-record",
    proofVersion: 1,
    encoding: "ble-payload-v1",
    recordCanonicalBytes: payloadBytes,
    recordSignature,
    backingRecordId,
    messageId,
  };

  const unsigned: UnsignedLedgerEvent = {
    version: 1,
    sosId: deriveSosId(authorPublic, messageId),
    type: "SOS_CREATED",
    previousSosEventHash: null,
    issuedAt,
    issuerPublicKey: authorPublic,
    payload,
    payloadHash,
    payloadVersion: 1,
    proof,
  };

  return { ...unsigned, eventId: computeEventId(unsigned) };
}

async function buildBackendEvent(
  type: "SOS_RESOLVED" | "SOS_CANCELLED" | "SOS_EXPIRED" | "KEY_REVOKED",
  backendPrivate: Uint8Array,
  backendPublic: Uint8Array,
  opts: {
    sosId: string;
    previousSosEventHash: string;
    issuedAt?: number;
    issuerPublicKey: Uint8Array;
    payload: UnsignedLedgerEvent["payload"];
    proof: UnsignedLedgerEvent["proof"];
    tamperSignature?: boolean;
  },
): Promise<LedgerEvent> {
  const issuedAt = opts.issuedAt ?? 1_800_000_001_000;
  const payloadHash = computePayloadHash(opts.payload);
  const unsigned: UnsignedLedgerEvent = {
    version: 1,
    sosId: opts.sosId,
    type,
    previousSosEventHash: opts.previousSosEventHash,
    issuedAt,
    issuerPublicKey: opts.issuerPublicKey,
    payload: opts.payload,
    payloadHash,
    payloadVersion: 1,
    proof: opts.proof,
  };
  const eventId = computeEventId(unsigned);
  let eventSignature = await ed.signAsync(canonicalEventBytes(unsigned), backendPrivate);
  if (opts.tamperSignature) {
    eventSignature = eventSignature.slice(0, 64);
    eventSignature[0] ^= 0xff;
  }
  return { ...unsigned, eventId, eventSignature };
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe("ledger canonical + ids", () => {
  test("canonicalJsonBytes stable under key reorder", () => {
    const a = canonicalJsonBytes({ a: 1, b: 2, c: { y: 1, x: 0 } });
    const b = canonicalJsonBytes({ c: { x: 0, y: 1 }, b: 2, a: 1 });
    expect(bytesToHex(a)).toBe(bytesToHex(b));
    // Uint8Array tagged form survives reorder inside objects too.
    const withBytes = canonicalJsonString({ k: new Uint8Array([1, 2, 3]) });
    expect(withBytes).toBe('{"k":{"$bytes":"010203"}}');
  });

  test("canonicalEventBytes changes when any field modified → eventId differs", async () => {
    const priv = ed.utils.randomPrivateKey();
    const pub = await ed.getPublicKeyAsync(priv);
    const e1 = await buildSosCreatedEvent(priv, pub, 7);
    const e2 = await buildSosCreatedEvent(priv, pub, 7, e1.issuedAt + 1);
    expect(e1.eventId).not.toBe(e2.eventId);
  });

  test("deriveSosId stable for (pubkey, msgId); distinct for different msgId", async () => {
    const priv = ed.utils.randomPrivateKey();
    const pub = await ed.getPublicKeyAsync(priv);
    const a = deriveSosId(pub, 11);
    const b = deriveSosId(pub, 11);
    const c = deriveSosId(pub, 12);
    expect(a).toBe(b);
    expect(a).not.toBe(c);
    expect(a).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe("ledger verifyLedgerEvent", () => {
  test("accepts SOS_CREATED derived from a real 22 B BLE payload (encoding=ble-payload-v1)", async () => {
    const priv = ed.utils.randomPrivateKey();
    const pub = await ed.getPublicKeyAsync(priv);
    const backendPub = await ed.getPublicKeyAsync(ed.utils.randomPrivateKey());
    const e = await buildSosCreatedEvent(priv, pub, 42);
    const res = await verifyLedgerEvent(e, backendPub);
    expect(res).toEqual({ ok: true });
  });

  test("rejects SOS_CREATED when recordSignature is wrong", async () => {
    const priv = ed.utils.randomPrivateKey();
    const pub = await ed.getPublicKeyAsync(priv);
    const otherPriv = ed.utils.randomPrivateKey();
    const payloadBytes = makeBlePayload(99);
    // Sign with a different key.
    const badSig = await ed.signAsync(payloadBytes, otherPriv);
    const backingRecordId = bytesToHex(sha256(payloadBytes));
    const payload: SosCreatedPayload = {
      payloadVersion: 1,
      backingRecordId,
      channel: SOS_CHANNEL,
      messageId: 99,
      createdAt: 1_800_000_000_000,
    };
    const proof: ChannelRecordProof = {
      proofType: "channel-record",
      proofVersion: 1,
      encoding: "ble-payload-v1",
      recordCanonicalBytes: payloadBytes,
      recordSignature: badSig,
      backingRecordId,
      messageId: 99,
    };
    const unsigned: UnsignedLedgerEvent = {
      version: 1,
      sosId: deriveSosId(pub, 99),
      type: "SOS_CREATED",
      previousSosEventHash: null,
      issuedAt: 1_800_000_000_000,
      issuerPublicKey: pub,
      payload,
      payloadHash: computePayloadHash(payload),
      payloadVersion: 1,
      proof,
    };
    const event: LedgerEvent = { ...unsigned, eventId: computeEventId(unsigned) };
    const res = await verifyLedgerEvent(event, pub);
    expect(res).toEqual({ ok: false, reason: "INVALID_SIGNATURE" });
  });

  test("rejects SOS_CREATED when an eventSignature is present", async () => {
    const priv = ed.utils.randomPrivateKey();
    const pub = await ed.getPublicKeyAsync(priv);
    const backendPriv = ed.utils.randomPrivateKey();
    const backendPub = await ed.getPublicKeyAsync(backendPriv);
    const e = await buildSosCreatedEvent(priv, pub, 5);
    const tampered: LedgerEvent = {
      ...e,
      eventSignature: await ed.signAsync(canonicalEventBytes(e), backendPriv),
    };
    const res = await verifyLedgerEvent(tampered, backendPub);
    expect(res).toEqual({ ok: false, reason: "INVALID_PROOF" });
  });

  test("rejects backend event with invalid eventSignature", async () => {
    const backendPriv = ed.utils.randomPrivateKey();
    const backendPub = await ed.getPublicKeyAsync(backendPriv);
    const authorPriv = ed.utils.randomPrivateKey();
    const authorPub = await ed.getPublicKeyAsync(authorPriv);
    const created = await buildSosCreatedEvent(authorPriv, authorPub, 3);
    const resolvePayload: ResolvePayload = {
      payloadVersion: 1,
      resolvedAt: 1_800_000_001_000,
      quorumReachedAt: 1_800_000_000_900,
      backendIssuedAt: 1_800_000_001_000,
      witnesses: [],
    };
    const resolveProof: ResolveQuorumProof = {
      proofType: "resolve-quorum",
      proofVersion: 1,
      resolveReceiptId: "r-" + "a".repeat(60),
      witnessEnvelopeHash: bytesToHex(
        (await import("@noble/hashes/sha2.js")).sha256(canonicalJsonBytes([])),
      ),
      quorumCount: 0,
    };
    const bad = await buildBackendEvent("SOS_RESOLVED", backendPriv, backendPub, {
      sosId: created.sosId,
      previousSosEventHash: created.eventId,
      issuerPublicKey: backendPub,
      payload: resolvePayload,
      proof: resolveProof,
      tamperSignature: true,
    });
    const res = await verifyLedgerEvent(bad, backendPub);
    expect(res).toEqual({ ok: false, reason: "INVALID_SIGNATURE" });
  });
});

describe("ledger applyEvent", () => {
  test("CREATED from null → accepted, active", async () => {
    const priv = ed.utils.randomPrivateKey();
    const pub = await ed.getPublicKeyAsync(priv);
    const created = await buildSosCreatedEvent(priv, pub, 1);
    const r = applyEvent(null, created, { createdEvent: created });
    expect(r.accepted).toBe(true);
    if (r.accepted) {
      expect(r.state.type).toBe("active");
      expect(r.state.terminal).toBe(false);
      expect(r.state.sosId).toBe(created.sosId);
      expect(r.state.lastEventId).toBe(created.eventId);
    }
  });

  test("RESOLVED without CREATED → MISSING_SOS_CREATED", async () => {
    const backendPriv = ed.utils.randomPrivateKey();
    const backendPub = await ed.getPublicKeyAsync(backendPriv);
    const authorPub = await ed.getPublicKeyAsync(ed.utils.randomPrivateKey());
    const created = await buildSosCreatedEvent(
      ed.utils.randomPrivateKey(),
      await ed.getPublicKeyAsync(ed.utils.randomPrivateKey()),
      77,
    );
    const resolved = await buildBackendEvent("SOS_RESOLVED", backendPriv, backendPub, {
      sosId: created.sosId,
      previousSosEventHash: created.eventId,
      issuerPublicKey: backendPub,
      payload: {
        payloadVersion: 1,
        resolvedAt: 1,
        quorumReachedAt: 1,
        backendIssuedAt: 1,
        witnesses: [],
      },
      proof: {
        proofType: "resolve-quorum",
        proofVersion: 1,
        resolveReceiptId: "x",
        witnessEnvelopeHash: bytesToHex(sha256(new Uint8Array())),
        quorumCount: 0,
      },
    });
    const r = applyEvent(null, resolved, { createdEvent: null });
    expect(r.accepted).toBe(false);
    if (!r.accepted) expect(r.reason).toBe("MISSING_SOS_CREATED");
    void authorPub;
  });

  test("RESOLVED on already-terminal CANCELLED → ILLEGAL_TRANSITION", async () => {
    const backendPriv = ed.utils.randomPrivateKey();
    const backendPub = await ed.getPublicKeyAsync(backendPriv);
    const authorPriv = ed.utils.randomPrivateKey();
    const authorPub = await ed.getPublicKeyAsync(authorPriv);
    const created = await buildSosCreatedEvent(authorPriv, authorPub, 8);
    const cancelled = await buildBackendEvent("SOS_CANCELLED", backendPriv, backendPub, {
      sosId: created.sosId,
      previousSosEventHash: created.eventId,
      issuerPublicKey: backendPub,
      payload: { payloadVersion: 1, cancelledAt: 2, reason: "victim" },
      proof: {
        proofType: "backend-ttl",
        proofVersion: 1,
        reason: "admin",
      },
    });
    const afterCreated = applyEvent(null, created, { createdEvent: created });
    if (!afterCreated.accepted) throw new Error("precondition: created accepted");
    const afterCancelled = applyEvent(afterCreated.state, cancelled, {
      createdEvent: created,
    });
    if (!afterCancelled.accepted) throw new Error("precondition: cancelled accepted");
    expect(afterCancelled.state.type).toBe("cancelled");
    expect(afterCancelled.state.terminal).toBe(true);

    const resolved = await buildBackendEvent("SOS_RESOLVED", backendPriv, backendPub, {
      sosId: created.sosId,
      previousSosEventHash: cancelled.eventId,
      issuerPublicKey: backendPub,
      payload: {
        payloadVersion: 1,
        resolvedAt: 3,
        quorumReachedAt: 3,
        backendIssuedAt: 3,
        witnesses: [],
      },
      proof: {
        proofType: "resolve-quorum",
        proofVersion: 1,
        resolveReceiptId: "y",
        witnessEnvelopeHash: bytesToHex(sha256(new Uint8Array())),
        quorumCount: 0,
      },
    });
    const r = applyEvent(afterCancelled.state, resolved, { createdEvent: created });
    expect(r.accepted).toBe(false);
    if (!r.accepted) expect(r.reason).toBe("ILLEGAL_TRANSITION");
  });
});

describe("ledger fork resolution", () => {
  async function makeResolveEvent(
    backendPriv: Uint8Array,
    backendPub: Uint8Array,
    sosId: string,
    parent: string,
    quorumCount: number,
    eventIdSalt: string,
  ): Promise<LedgerEvent> {
    const witnesses: ResolveWitness[] = [];
    for (let i = 0; i < quorumCount; i++) {
      const wPriv = ed.utils.randomPrivateKey();
      const wPub = bytesToHex(await ed.getPublicKeyAsync(wPriv));
      witnesses.push({
        deviceId: `device-${wPub}`,
        pubkey: wPub,
        lat: 1 + i,
        lon: 2 + i,
        ts: 1_800_000_000_000 + i,
        imageHash: bytesToHex(sha256(new Uint8Array([i]))),
        sig: bytesToHex(await ed.signAsync(new Uint8Array([i]), wPriv)),
      });
    }
    const payload: ResolvePayload = {
      payloadVersion: 1,
      resolvedAt: 1_800_000_002_000,
      quorumReachedAt: 1_800_000_001_900,
      backendIssuedAt: 1_800_000_002_000,
      witnesses,
    };
    const witnessEnvelopeHash = bytesToHex(sha256(canonicalJsonBytes(payload.witnesses)));
    const proof: ResolveQuorumProof = {
      proofType: "resolve-quorum",
      proofVersion: 1,
      resolveReceiptId: `r-${eventIdSalt}-${"a".repeat(50)}`,
      witnessEnvelopeHash,
      quorumCount,
    };
    // Vary issuedAt with salt hash so eventId differs even with same quorumCount.
    const saltHash = bytesToHex(sha256(new TextEncoder().encode(eventIdSalt))).slice(0, 8);
    const issuedAt = 1_800_000_002_000 + parseInt(saltHash, 16) % 1000;
    return buildBackendEvent("SOS_RESOLVED", backendPriv, backendPub, {
      sosId,
      previousSosEventHash: parent,
      issuedAt,
      issuerPublicKey: backendPub,
      payload,
      proof,
    });
  }

  test("compareAuthority: equal rank → higher quorumCount wins; tie → eventId asc", async () => {
    const backendPriv = ed.utils.randomPrivateKey();
    const backendPub = await ed.getPublicKeyAsync(backendPriv);
    const a = await makeResolveEvent(backendPriv, backendPub, "a".repeat(64), "b".repeat(64), 3, "A");
    const b = await makeResolveEvent(backendPriv, backendPub, "a".repeat(64), "b".repeat(64), 5, "B");
    // Higher quorumCount outranks lower.
    expect(compareAuthority(a, b)).toBeGreaterThan(0);
    expect(compareAuthority(b, a)).toBeLessThan(0);

    const c1 = await makeResolveEvent(backendPriv, backendPub, "c".repeat(64), "d".repeat(64), 4, "C");
    const c2 = await makeResolveEvent(backendPriv, backendPub, "c".repeat(64), "d".repeat(64), 4, "D");
    // Equal rank + quorum → eventId ascending wins; comparator returns negative
    // when first arg outranks second.
    const [lo, hi] = [c1.eventId, c2.eventId].sort((x, y) => x.localeCompare(y));
    const loEvt = c1.eventId === lo ? c1 : c2;
    const hiEvt = c1.eventId === lo ? c2 : c1;
    expect(compareAuthority(loEvt, hiEvt)).toBeLessThan(0);
    expect(compareAuthority(hiEvt, loEvt)).toBeGreaterThan(0);
  });

  test("reconcileState: orphan RESOLVED with missing parent, then CREATED appears → attached + state materializes", async () => {
    const backendPriv = ed.utils.randomPrivateKey();
    const backendPub = await ed.getPublicKeyAsync(backendPriv);
    const authorPriv = ed.utils.randomPrivateKey();
    const authorPub = await ed.getPublicKeyAsync(authorPriv);
    const created = await buildSosCreatedEvent(authorPriv, authorPub, 301);
    const resolved = await makeResolveEvent(
      backendPriv,
      backendPub,
      created.sosId,
      created.eventId,
      3,
      "Z",
    );

    // First: only the RESOLVED is known. CREATED parent is absent → orphan.
    const orphanView = reconcileState([resolved]);
    expect(orphanView.state).toBeNull();
    expect(orphanView.orphans).toContain(resolved.eventId);
    expect(orphanView.attached).toEqual([]);

    // Now the CREATED appears. RESOLVED links to it → chain attaches, state resolves.
    const attachedView = reconcileState([created, resolved]);
    expect(attachedView.state?.type).toBe("resolved");
    expect(attachedView.state?.terminal).toBe(true);
    expect(attachedView.state?.witnessCount).toBe(3);
    expect(attachedView.attached).toContain(created.eventId);
    expect(attachedView.attached).toContain(resolved.eventId);
    expect(attachedView.orphans).toEqual([]);
    expect(attachedView.conflicts).toEqual([]);
  });
});
