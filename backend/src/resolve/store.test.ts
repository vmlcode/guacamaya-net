import { describe, test, expect, beforeEach } from "bun:test";
import type { ResolveReceipt } from "@guacamaya/shared";
import { resolveStore } from "./store.js";

function makeReceipt(overrides: Partial<ResolveReceipt> = {}): ResolveReceipt {
  return {
    id: `receipt-${Math.random().toString(36).slice(2)}`,
    targetSosId: "a".repeat(64),
    targetSosAuthor: "device-" + "11".repeat(32),
    status: "pending",
    quorumNeeded: 2,
    quorumSeen: 1,
    witnessDeviceIds: ["device-" + "11".repeat(32)],
    createdAt: Date.now(),
    ...overrides,
  };
}

describe("resolveStore", () => {
  beforeEach(() => {
    resolveStore.clear();
  });

  test("upsertReceipt does NOT register pending-clear while accumulating witnesses", () => {
    resolveStore.upsertReceipt(makeReceipt({ quorumSeen: 1, cooldownEndsAt: undefined }));
    expect(resolveStore.getPendingClear("a".repeat(64))).toBeUndefined();
  });

  test("upsertReceipt registers pending-clear when cooldown starts", () => {
    const cooldownEndsAt = Date.now() + 15 * 60_000;
    resolveStore.upsertReceipt(makeReceipt({ quorumSeen: 2, cooldownEndsAt }));
    const pc = resolveStore.getPendingClear("a".repeat(64));
    expect(pc).toBeDefined();
    expect(pc!.cooldownEndsAt).toBe(cooldownEndsAt);
    expect(pc!.disputed).toBe(false);
  });

  test("promoteExpiredClears promotes expired cooldown to cleared", () => {
    const now = Date.now();
    const target = "b".repeat(64);
    resolveStore.upsertReceipt(makeReceipt({ targetSosId: target, cooldownEndsAt: now - 1000 }));
    const promoted = resolveStore.promoteExpiredClears(now);
    expect(promoted.length).toBe(1);
    expect(promoted[0].status).toBe("cleared");
    expect(resolveStore.getPendingClear(target)).toBeUndefined();
  });

  test("autoDispute marks pending-clear and prevents promotion", () => {
    const now = Date.now();
    const target = "c".repeat(64);
    resolveStore.upsertReceipt(makeReceipt({ targetSosId: target, cooldownEndsAt: now - 1000 }));
    const disputed = resolveStore.autoDispute(target, "originator_refire");
    expect(disputed!.status).toBe("disputed");
    expect(resolveStore.getPendingClear(target)!.disputed).toBe(true);
    expect(resolveStore.promoteExpiredClears(now).length).toBe(0);
  });

  test("findPendingClearForAuthor only finds receipts in real cooldown", () => {
    const author = "device-" + "dd".repeat(32);
    const accumulating = "d".repeat(64);
    const cooldown = "e".repeat(64);
    resolveStore.upsertReceipt(makeReceipt({ targetSosId: accumulating, targetSosAuthor: author, cooldownEndsAt: undefined }));
    resolveStore.upsertReceipt(makeReceipt({ targetSosId: cooldown, targetSosAuthor: author, cooldownEndsAt: Date.now() + 60_000 }));
    expect(resolveStore.findPendingClearForAuthor(author, "x".repeat(64))).toBe(cooldown);
  });

  test("upserting same target replaces previous receipt", () => {
    const target = "ff".repeat(32);
    resolveStore.upsertReceipt(makeReceipt({ targetSosId: target, quorumSeen: 1, cooldownEndsAt: undefined }));
    expect(resolveStore.getReceiptByTarget(target)!.quorumSeen).toBe(1);
    expect(resolveStore.getPendingClear(target)).toBeUndefined();

    resolveStore.upsertReceipt(makeReceipt({ targetSosId: target, quorumSeen: 2, cooldownEndsAt: Date.now() + 15 * 60_000 }));
    expect(resolveStore.getReceiptByTarget(target)!.quorumSeen).toBe(2);
    expect(resolveStore.getPendingClear(target)).toBeDefined();
  });
});
