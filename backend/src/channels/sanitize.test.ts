import { describe, expect, test } from "bun:test";
import type { ChannelRecord } from "@guacamaya/shared";
import { sanitizeRecordForPublic, sanitizeRecordsForPublic } from "./sanitize.js";

function sosRecord(overrides: Partial<ChannelRecord> = {}): ChannelRecord {
  return {
    id: "abc",
    channel: "solicito-ayuda",
    timestamp: 1719500000000,
    ttl: 0,
    author: "device-deadbeef",
    verified: false,
    payload: {
      source: "mesh-ble",
      nodeId: "1234abcd",
      lat: 10.4912345,
      lon: -66.8798765,
      sosType: "violence",
      critical: true,
      frameB64: "AAAA",
    },
    sig: "ff",
    ...overrides,
  };
}

describe("sanitizeRecordForPublic", () => {
  test("coarsens lat/lon to ~1km on community SOS channel", () => {
    const out = sanitizeRecordForPublic(sosRecord());
    const p = out.payload as Record<string, unknown>;
    expect(p.lat).toBe(10.49);
    expect(p.lon).toBe(-66.88);
    expect(p.locationPrecision).toBe("coarse");
  });

  test("drops frameB64 (it encodes exact coords)", () => {
    const out = sanitizeRecordForPublic(sosRecord());
    const p = out.payload as Record<string, unknown>;
    expect("frameB64" in p).toBe(false);
  });

  test("keeps sosType / critical / timestamp visible", () => {
    const out = sanitizeRecordForPublic(sosRecord());
    const p = out.payload as Record<string, unknown>;
    expect(p.sosType).toBe("violence");
    expect(p.critical).toBe(true);
    expect(out.timestamp).toBe(1719500000000);
  });

  test("applies to estoy-bien too", () => {
    const out = sanitizeRecordForPublic(sosRecord({ channel: "estoy-bien" }));
    const p = out.payload as Record<string, unknown>;
    expect(p.lat).toBe(10.49);
    expect("frameB64" in p).toBe(false);
  });

  test("leaves official channels untouched (full precision, full payload)", () => {
    const rec = sosRecord({
      channel: "alertas",
      verified: true,
      payload: { lat: 10.4912345, lon: -66.8798765, message: "evacuar" },
    });
    const out = sanitizeRecordForPublic(rec);
    const p = out.payload as Record<string, unknown>;
    expect(p.lat).toBe(10.4912345);
    expect(p.lon).toBe(-66.8798765);
    expect(p.locationPrecision).toBeUndefined();
  });

  test("does not mutate the input record", () => {
    const rec = sosRecord();
    sanitizeRecordForPublic(rec);
    const p = rec.payload as Record<string, unknown>;
    expect(p.lat).toBe(10.4912345);
    expect(p.frameB64).toBe("AAAA");
  });

  test("tolerates records without a coordinate payload", () => {
    const rec = sosRecord({ payload: { sosType: "other" } });
    const out = sanitizeRecordForPublic(rec);
    const p = out.payload as Record<string, unknown>;
    expect(p.sosType).toBe("other");
    expect(p.lat).toBeUndefined();
  });

  test("sanitizeRecordsForPublic maps over the batch", () => {
    const out = sanitizeRecordsForPublic([sosRecord(), sosRecord({ channel: "alertas" })]);
    expect((out[0]!.payload as Record<string, unknown>).lat).toBe(10.49);
    expect((out[1]!.payload as Record<string, unknown>).lat).toBe(10.4912345);
  });
});
