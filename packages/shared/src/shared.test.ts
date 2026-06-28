import { expect, test, describe } from "bun:test";
import * as ed from "@noble/ed25519";
import { hexToBytes, bytesToHex } from "@noble/hashes/utils.js";
import { getRecordId, verifyRecordSignature } from "./crypto.js";
import { mergeLogs } from "./merge.js";
import { ChannelRecord } from "./types.js";

describe("GuacaMalla Shared Utilities", () => {
  const privKey = ed.utils.randomPrivateKey();
  let pubKeyHex: string;

  test("deriving public key", async () => {
    const pubKey = await ed.getPublicKeyAsync(privKey);
    pubKeyHex = bytesToHex(pubKey);
    expect(pubKeyHex).toBeDefined();
  });

  test("deterministic hashing (getRecordId)", () => {
    const record1 = {
      channel: "alertas" as const,
      timestamp: 1700000000000,
      ttl: 3,
      author: "backend",
      verified: true,
      payload: { text: "Earthquake alert!" }
    };

    const record2 = {
      channel: "alertas" as const,
      timestamp: 1700000000000,
      ttl: 3,
      author: "backend",
      verified: true,
      payload: { text: "Earthquake alert!" }
    };

    const id1 = getRecordId(record1);
    const id2 = getRecordId(record2);

    expect(id1).toBe(id2);
    expect(id1.length).toBe(64); // SHA-256 hex is 64 chars
  });

  test("signature generation & verification", async () => {
    const record: Omit<ChannelRecord, "id" | "sig"> & { verified: true } = {
      channel: "alertas",
      timestamp: Date.now(),
      ttl: 3,
      author: "backend",
      verified: true,
      payload: { text: "Evacuate Zone A" }
    };

    const hash = getRecordId(record);
    const hashBytes = hexToBytes(hash);
    const sigBytes = await ed.signAsync(hashBytes, privKey);
    const sigHex = bytesToHex(sigBytes);

    const fullRecord: ChannelRecord = {
      ...record,
      id: hash,
      sig: sigHex
    };

    const isValid = await verifyRecordSignature(fullRecord, pubKeyHex);
    expect(isValid).toBe(true);

    // Tamper with record payload
    const tamperedRecord = {
      ...fullRecord,
      payload: { text: "All safe in Zone A" }
    };
    const isTamperedValid = await verifyRecordSignature(tamperedRecord, pubKeyHex);
    expect(isTamperedValid).toBe(false);
  });

  test("mergeLogs - deduplication and sorting", async () => {
    const logA: ChannelRecord[] = [
      {
        id: "1",
        channel: "estoy-bien",
        timestamp: 1000,
        ttl: 3,
        author: "device-1",
        verified: false,
        payload: "I'm OK"
      },
      {
        id: "2",
        channel: "estoy-bien",
        timestamp: 3000,
        ttl: 3,
        author: "device-2",
        verified: false,
        payload: "All good"
      }
    ];

    const logB: ChannelRecord[] = [
      {
        id: "2", // duplicate
        channel: "estoy-bien",
        timestamp: 3000,
        ttl: 3,
        author: "device-2",
        verified: false,
        payload: "All good"
      },
      {
        id: "3", // new, older timestamp
        channel: "estoy-bien",
        timestamp: 2000,
        ttl: 3,
        author: "device-3",
        verified: false,
        payload: "Need water"
      }
    ];

    const merged = await mergeLogs(logA, logB);

    expect(merged.length).toBe(3);
    // Should be sorted by timestamp: 1 (1000), 3 (2000), 2 (3000)
    expect(merged[0].id).toBe("1");
    expect(merged[1].id).toBe("3");
    expect(merged[2].id).toBe("2");
  });
});
