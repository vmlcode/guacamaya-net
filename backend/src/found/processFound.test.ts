import { describe, expect, test } from "bun:test";
import { buildFoundFrame, type ChannelRecord } from "@guacamaya/shared";
import { processFoundFrames, type FoundDeps, FOUND_CHANNEL } from "./processFound.js";

const sk = new Uint8Array(32).fill(9);
const TARGET_NODE_ID = new Uint8Array([0x11, 0x22, 0x33, 0x44]);
const TARGET_NODE_ID_HEX = "11223344";
const TARGET_MSG_ID = 100;
// The SOS victim's last reported point.
const SOS_LAT = 10.4;
const SOS_LON = -66.9;

function makeDeps(overrides: Partial<FoundDeps> = {}) {
  const store = new Map<string, ChannelRecord>();
  const broadcasts: ChannelRecord[] = [];
  const deps: FoundDeps = {
    proximityNearMeters: 100,
    async findTargetSos(nodeIdHex, msgId) {
      if (nodeIdHex === TARGET_NODE_ID_HEX && msgId === TARGET_MSG_ID) {
        return { recordId: "sos-record-1", lat: SOS_LAT, lon: SOS_LON };
      }
      return null;
    },
    async addRecord(record) {
      if (store.has(record.id)) return false;
      store.set(record.id, record);
      return true;
    },
    broadcast(record) {
      broadcasts.push(record);
    },
    ...overrides,
  };
  return { deps, store, broadcasts };
}

async function frameB64(latE7: number, lonE7: number, foundMsgId = 1): Promise<string> {
  const frame = await buildFoundFrame(
    {
      latE7,
      lonE7,
      tsUnix: 1_750_000_000,
      targetNodeId: TARGET_NODE_ID,
      targetMsgId: TARGET_MSG_ID,
      foundMsgId,
    },
    sk,
  );
  return Buffer.from(frame).toString("base64");
}

describe("processFoundFrames", () => {
  test("finder ~33 m away → accepted, near, likely_found", async () => {
    const { deps, store, broadcasts } = makeDeps();
    const summary = await processFoundFrames([await frameB64(104_003_000, -669_000_000)], deps);
    expect(summary.accepted).toBe(1);
    expect(summary.rejected).toBe(0);
    expect(summary.results).toHaveLength(1);
    expect(summary.results[0]!.proximity).toBe("near");
    expect(summary.results[0]!.verdict).toBe("likely_found");
    expect(summary.results[0]!.targetRecordId).toBe("sos-record-1");
    expect(summary.results[0]!.distanceMeters).toBeLessThan(100);
    expect(store.size).toBe(1);
    expect(broadcasts).toHaveLength(1);
    const record = [...store.values()][0]!;
    expect(record.channel).toBe(FOUND_CHANNEL);
    expect(record.verified).toBe(false);
    expect(record.author.startsWith("device-")).toBe(true);
    const p = record.payload as { verdict: string; distanceMeters: number; source: string };
    expect(p.source).toBe("mesh-found");
    expect(p.verdict).toBe("likely_found");
  });

  test("finder ~1.1 km away → accepted but far, possible_false_positive", async () => {
    const { deps } = makeDeps();
    const summary = await processFoundFrames([await frameB64(104_100_000, -669_000_000)], deps);
    expect(summary.accepted).toBe(1);
    expect(summary.results[0]!.proximity).toBe("far");
    expect(summary.results[0]!.verdict).toBe("possible_false_positive");
    expect(summary.results[0]!.distanceMeters).toBeGreaterThan(1000);
  });

  test("unknown target SOS → rejected", async () => {
    const { deps } = makeDeps({ findTargetSos: async () => null });
    const summary = await processFoundFrames([await frameB64(104_003_000, -669_000_000)], deps);
    expect(summary.accepted).toBe(0);
    expect(summary.rejected).toBe(1);
    expect(summary.reasons["unknown target sos"]).toBe(1);
    expect(summary.results).toHaveLength(0);
  });

  test("re-upload of the same frame → duplicate, verdict still returned", async () => {
    const { deps } = makeDeps();
    const b64 = await frameB64(104_003_000, -669_000_000);
    await processFoundFrames([b64], deps);
    const second = await processFoundFrames([b64], deps);
    expect(second.accepted).toBe(0);
    expect(second.duplicate).toBe(1);
    expect(second.results).toHaveLength(1);
    expect(second.results[0]!.verdict).toBe("likely_found");
  });

  test("garbage frame → rejected without touching the store", async () => {
    const { deps, store } = makeDeps();
    const summary = await processFoundFrames(["!!!not-base64!!!", "QUJD"], deps);
    expect(summary.accepted).toBe(0);
    expect(summary.rejected).toBe(2);
    expect(store.size).toBe(0);
  });
});
