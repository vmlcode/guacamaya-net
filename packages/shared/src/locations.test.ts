import { expect, test, describe } from "bun:test";
import { getLocationId } from "./crypto.js";
import { LocationPoint } from "./types.js";

describe("getLocationId", () => {
  const base = { deviceId: "device-ab12", lat: 10.49, lon: -66.87, timestamp: 1719500000000, accuracy: 12 };

  test("deterministic — same input yields same id", () => {
    expect(getLocationId(base)).toBe(getLocationId({ ...base }));
  });

  test("id is 64-char hex (SHA-256)", () => {
    expect(getLocationId(base)).toMatch(/^[0-9a-f]{64}$/);
  });

  test("different lat produces different id", () => {
    expect(getLocationId(base)).not.toBe(getLocationId({ ...base, lat: 10.50 }));
  });

  test("different deviceId produces different id", () => {
    expect(getLocationId(base)).not.toBe(getLocationId({ ...base, deviceId: "device-cd34" }));
  });

  test("same content from two mules yields same id (dedup invariant)", () => {
    // Simulates mule-B uploading the same point that mule-A already uploaded.
    const pointFromMuleA = { ...base };
    const pointFromMuleB = { ...base }; // identical content, different uploader — irrelevant
    expect(getLocationId(pointFromMuleA)).toBe(getLocationId(pointFromMuleB));
  });

  test("missing accuracy treated as empty string — stable id", () => {
    const withAccuracy = { ...base, accuracy: undefined };
    const withoutAccuracy = { deviceId: base.deviceId, lat: base.lat, lon: base.lon, timestamp: base.timestamp };
    expect(getLocationId(withAccuracy)).toBe(getLocationId(withoutAccuracy));
  });
});
