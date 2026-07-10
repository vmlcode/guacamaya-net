import { describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { bytesToHex, hexToBytes } from "@noble/hashes/utils.js";
import { ReceiptReplayGuard, hash, issueRescuerCertificate, receiptStopsVictimBroadcast, signControlReceipt, verifyControlReceipt } from "./control.js";

describe("control receipts", () => {
  test("valid certified soft resolve is visual-only and replay protected", async () => {
    const backendPrivate = ed.utils.randomPrivateKey();
    const backendPublic = bytesToHex(await ed.getPublicKeyAsync(backendPrivate));
    const rescuerPrivate = ed.utils.randomPrivateKey();
    const rescuerPublic = bytesToHex(await ed.getPublicKeyAsync(rescuerPrivate));
    const now = 1_800_000_000_000;
    const certificate = await issueRescuerCertificate(rescuerPublic, now + 60_000, backendPrivate, now);
    const receipt = await signControlReceipt({
      type: "soft-resolve", targetSosId: "a".repeat(64), targetSosAuthor: "device-" + "b".repeat(64),
      issuedAt: now, expiresAt: now + 120_000, issuerPublicKey: rescuerPublic, certificate,
    }, rescuerPrivate);
    expect(await verifyControlReceipt(receipt, backendPublic, now + 1)).toBe(true);
    // Cert expired in transit, but it was valid when the rescuer signed.
    expect(await verifyControlReceipt(receipt, backendPublic, now + 90_000)).toBe(true);
    expect(receiptStopsVictimBroadcast(receipt)).toBe(false);
    const guard = new ReceiptReplayGuard();
    expect(guard.admit(receipt, now + 1)).toBe(true);
    expect(guard.admit(receipt, now + 2)).toBe(false);
  });

  test("a rescuer receipt cannot target a later SOS without matching target id", async () => {
    const backendPrivate = ed.utils.randomPrivateKey();
    const backendPublic = bytesToHex(await ed.getPublicKeyAsync(backendPrivate));
    const rescuerPrivate = ed.utils.randomPrivateKey();
    const rescuerPublic = bytesToHex(await ed.getPublicKeyAsync(rescuerPrivate));
    const now = 1_800_000_000_000;
    const cert = await issueRescuerCertificate(rescuerPublic, now + 60_000, backendPrivate, now);
    const receipt = await signControlReceipt({ type: "soft-resolve", targetSosId: "a".repeat(64), targetSosAuthor: "device-" + "b".repeat(64), issuedAt: now, expiresAt: now + 60_000, issuerPublicKey: rescuerPublic, certificate: cert }, rescuerPrivate);
    const replayedAgainstNewSos = { ...receipt, targetSosId: "c".repeat(64) };
    expect(await verifyControlReceipt(replayedAgainstNewSos, backendPublic, now + 1)).toBe(false);
  });

  // Golden vector produced with the historical local canonicalizer before it was
  // replaced by canonical.ts. A receipt signed under the old bytes must still
  // hash and verify identically — this pins the wire compatibility.
  test("canonicalizer swap keeps historical receipt bytes and signatures valid", async () => {
    const priv = hexToBytes("11".repeat(32));
    const pub = bytesToHex(await ed.getPublicKeyAsync(priv));
    expect(pub).toBe("d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737");
    const unsigned = {
      type: "self-cancel" as const, targetSosId: "a".repeat(64), targetSosAuthor: `device-${pub}`,
      issuedAt: 1_800_000_000_000, expiresAt: 1_800_000_060_000, issuerPublicKey: pub,
    };
    expect(bytesToHex(hash("guacamaya.control-receipt.v1", unsigned)))
      .toBe("bae1064e71ae01c558fef26c44cfae63b6f909950b3c807f18f6028cdda50c04");
    const legacySignature = "d80f776e8d4eb3442b27e103f8906c61e6d94e9c22a9311996b78ff10018a4981894c3bc539c4342222e7a5cc2dd3ce2ad5b254f73b0438a86c845d3f2a9ee01";
    const receipt = { ...unsigned, signature: legacySignature };
    expect(await verifyControlReceipt(receipt, "f".repeat(64), unsigned.issuedAt + 1)).toBe(true);
    // Freshly signed receipts produce the exact same signature bytes.
    expect((await signControlReceipt(unsigned, priv)).signature).toBe(legacySignature);
  });
});
