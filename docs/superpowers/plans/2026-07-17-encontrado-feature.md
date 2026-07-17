# "Encontrado" (Found) Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Suggested subagent models:** Tasks 1–3 (shared wire format + backend) and Tasks 4–7 (Android) involve crypto/wire code — dispatch **sonnet** subagents. Task 8 (docs/CHANGELOG) is mechanical — a **haiku** subagent is enough. Task order: 1 → 2 → 3 must be sequential; Tasks 4–7 must be sequential among themselves but the Android chain (4–7) may start as soon as Task 1 is committed (the wire format is frozen there). Task 8 runs last.

**Goal:** Let a finder mark an SOS victim as *encontrado* (found) by target ID + finder location; the backend validates the finder's proximity to the SOS's last reported point (≤100 m = "near / likely found", further = "far / ⚠️ possible false positive") and stores the report as a community record.

**Architecture:** A new signed 118-byte "found frame" (22 B payload + 32 B finder pubkey + 64 B Ed25519 signature, domain-separated `guacamaya.found-frame.v1\n`) travels from the Android app to the backend inside the existing `POST /ingest` body as a new `foundFrames` array. The backend re-verifies each frame zero-trust, looks up the target SOS by `(targetNodeId, targetMsgId)` on channel `solicito-ayuda`, computes the haversine distance from the finder's signed GPS position to the SOS's last reported point, and persists a `verified:false` record on a new community channel `encontrado` carrying the verdict. The Android app adds a "Marcar como encontrado" action on the Radar screen with a local near/far pre-check dialog, queues the signed frame in Room, and drains it through the existing data-mule uploader.

**Tech Stack:** Bun + TypeScript + Fastify (backend), `@noble/ed25519` / `@noble/hashes`, `bun:test`; Kotlin + Jetpack Compose + Room + WorkManager (Android), JUnit4 unit tests.

## Global Constraints

- **Bun only** — never npm/node. All backend/shared commands run from the repo root (`/Users/carlosramirez/desarrollo/hackathons/build4venezuela/guacamaya-net`). Android Gradle commands run from `android/`.
- **Code identifiers stay `guacamaya`** (packages `@guacamaya/shared`, Kotlin package `net.guacamaya`, signing domains `guacamaya.*`). Never rename them to `guacamalla`. Prose/UI copy uses **GuacaMalla**.
- **Wire format must be byte-identical on both sides**: the TypeScript encoder/verifier in `packages/shared/src/mesh/` and the Kotlin encoder in `android/app/src/main/kotlin/net/guacamaya/proto/` implement the exact same 22-byte big-endian layout and the exact same signature domain string `"guacamaya.found-frame.v1\n"` (note the trailing `\n`).
- **All signatures are domain-separated** — the found-frame signature covers `UTF8("guacamaya.found-frame.v1\n") || payload22`, never the bare payload.
- **Zero-trust ingest**: the backend never persists a frame whose signature it hasn't re-verified, and always computes record `id` server-side (`SHA-256(payload22)` hex).
- **User-facing copy is Spanish** (matches existing UI: "Marcar como encontrado", "posible falso positivo").
- `packages/shared` stays framework-agnostic — no backend-only deps in it.
- Threshold default is **100 meters**, env-overridable on the backend via `FOUND_PROXIMITY_METERS`.

## Wire format (frozen in Task 1 — every other task consumes this verbatim)

**Found payload v1 — 22 bytes, big-endian:**

| Offset | Field | Type | Meaning |
|---|---|---|---|
| 0..3 | `latE7` | int32 | Finder latitude × 1e7 (where the person was found) |
| 4..7 | `lonE7` | int32 | Finder longitude × 1e7 |
| 8..11 | `tsUnix` | u32 | Unix seconds when marked found |
| 12..15 | `targetNodeId` | 4 B | SHA-256(victim pubkey)[0..3] — the SOS node being marked |
| 16..17 | `targetMsgId` | u16 | msgId of the SOS session being marked |
| 18..19 | `foundMsgId` | u16 | CSPRNG per found report (dedupe entropy) |
| 20..21 | `crc` | u16 | CRC16-CCITT over bytes 0..19 |

**Frame (118 B upload form):** `payload22 + finderPubkey32 + sig64`. Signature = Ed25519 over `UTF8("guacamaya.found-frame.v1\n") || payload22`. A 119-byte frame (leading hop-TTL byte) is tolerated by stripping the first byte, same as SOS/control frames.

**Backend verdict rule:** `distanceMeters = haversine(finder latE7/lonE7, target SOS last reported lat/lon)`. `distanceMeters ≤ FOUND_PROXIMITY_METERS (default 100)` → `proximity: "near"`, `verdict: "likely_found"`; otherwise `proximity: "far"`, `verdict: "possible_false_positive"`. If no SOS record matches `(targetNodeId, targetMsgId)` (or it has no GPS fix), the frame is **rejected** with reason `"unknown target sos"` — you cannot mark found what was never reported.

---

### Task 1: Shared wire format — `foundFrame.ts`, geo helper, constants

**Files:**
- Modify: `packages/shared/src/mesh/constants.ts`
- Create: `packages/shared/src/geo.ts`
- Create: `packages/shared/src/mesh/foundFrame.ts`
- Modify: `packages/shared/src/mesh/index.ts`
- Modify: `packages/shared/src/index.ts`
- Test: `packages/shared/src/mesh/foundFrame.test.ts`

**Interfaces:**
- Consumes: existing `crc16Ccitt(bytes, start, end)` from `packages/shared/src/mesh/crc16.ts`, `MESH_PUBKEY_LEN`/`MESH_SIG_LEN` from `constants.ts`.
- Produces (used by Tasks 2–3):
  - `FOUND_SIGN_DOMAIN: string`, `FOUND_PAYLOAD_LEN/FOUND_FRAME_LEN/FOUND_ON_WIRE_LEN/FOUND_PAYLOAD_OFF/FOUND_PUBKEY_OFF/FOUND_SIG_OFF: number`, `FOUND_PROXIMITY_NEAR_METERS = 100`
  - `haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number`
  - `encodeFoundPayload(fields: FoundPayloadFields): Uint8Array`
  - `foundSignBytes(payload: Uint8Array): Uint8Array`
  - `buildFoundFrame(fields: FoundPayloadFields, privateKey: Uint8Array): Promise<Uint8Array>`
  - `verifyFoundFrame(bytes: Uint8Array): Promise<FoundFrameResult>` where `FoundFrameResult = { ok: true; found: VerifiedFoundFrame } | { ok: false; reason: string }` and `VerifiedFoundFrame = { latE7, lonE7, tsUnix, targetMsgId, foundMsgId: number; targetNodeId: Uint8Array; lat, lon: number; targetNodeIdHex, finderPublicKeyHex, finderNodeIdHex, signatureHex: string; payload: Uint8Array; frame: Uint8Array }`

- [ ] **Step 1: Add constants**

Append to `packages/shared/src/mesh/constants.ts` (at the end of the file):

```ts
/**
 * Found frame v1 ("Encontrado") — a finder marks an SOS victim as found.
 * Same envelope as the SOS frame: 22 B payload + 32 B pubkey + 64 B signature,
 * with a leading mutable hop-TTL byte tolerated on the wire. Must stay
 * byte-identical with net.guacamaya.proto.FoundPayload (Android).
 *
 *   off 0..3   latE7         int32 — finder latitude × 1e7
 *   off 4..7   lonE7         int32 — finder longitude × 1e7
 *   off 8..11  tsUnix        u32 unix seconds
 *   off 12..15 targetNodeId  SHA-256(victim pubkey)[0..3]
 *   off 16..17 targetMsgId   u16 — msgId of the SOS session being marked
 *   off 18..19 foundMsgId    u16 — CSPRNG per found report (dedupe entropy)
 *   off 20..21 crc           CRC16-CCITT over bytes 0..19
 */
export const FOUND_PAYLOAD_LEN = 22;
export const FOUND_FRAME_LEN = FOUND_PAYLOAD_LEN + MESH_PUBKEY_LEN + MESH_SIG_LEN; // 118 (upload, TTL stripped)
export const FOUND_ON_WIRE_LEN = FOUND_FRAME_LEN + 1; // 119 (leading hop TTL)

export const FOUND_PAYLOAD_OFF = 0;
export const FOUND_PUBKEY_OFF = FOUND_PAYLOAD_OFF + FOUND_PAYLOAD_LEN;
export const FOUND_SIG_OFF = FOUND_PUBKEY_OFF + MESH_PUBKEY_LEN;

/**
 * Domain separation for the found-frame Ed25519 signature:
 * sig = Ed25519(sk, UTF8(FOUND_SIGN_DOMAIN) || payload22).
 * An SOS or control signature can never be replayed as a found report or
 * vice versa. Mirrored verbatim in net.guacamaya.proto.FoundPayload.
 */
export const FOUND_SIGN_DOMAIN = "guacamaya.found-frame.v1\n";

/** Default proximity gate: finder within this many meters of the SOS's last
 * reported point → "near / likely_found"; further → "far / possible_false_positive".
 * Backend default; env-overridable via FOUND_PROXIMITY_METERS. */
export const FOUND_PROXIMITY_NEAR_METERS = 100;
```

- [ ] **Step 2: Create the geo helper**

Create `packages/shared/src/geo.ts`:

```ts
/** Great-circle distance in meters between two WGS-84 points (haversine). */
export function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6_371_000; // mean Earth radius, meters
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
```

- [ ] **Step 3: Write the failing test**

Create `packages/shared/src/mesh/foundFrame.test.ts`:

```ts
import { describe, expect, test } from "bun:test";
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { crc16Ccitt } from "./crc16.js";
import { FOUND_FRAME_LEN, FOUND_SIG_OFF, FOUND_PUBKEY_OFF } from "./constants.js";
import {
  buildFoundFrame,
  encodeFoundPayload,
  verifyFoundFrame,
  type FoundPayloadFields,
} from "./foundFrame.js";
import { haversineMeters } from "../geo.js";

const sk = new Uint8Array(32).fill(7);

function fields(overrides: Partial<FoundPayloadFields> = {}): FoundPayloadFields {
  return {
    latE7: 104_003_000, // 10.4003
    lonE7: -669_000_000, // -66.9
    tsUnix: 1_750_000_000,
    targetNodeId: new Uint8Array([0xaa, 0xbb, 0xcc, 0xdd]),
    targetMsgId: 4242,
    foundMsgId: 777,
    ...overrides,
  };
}

describe("encodeFoundPayload", () => {
  test("byte layout is big-endian at the documented offsets with a valid CRC", () => {
    const p = encodeFoundPayload(fields());
    expect(p.length).toBe(22);
    const dv = new DataView(p.buffer, p.byteOffset, p.byteLength);
    expect(dv.getInt32(0, false)).toBe(104_003_000);
    expect(dv.getInt32(4, false)).toBe(-669_000_000);
    expect(dv.getUint32(8, false)).toBe(1_750_000_000);
    expect([...p.subarray(12, 16)]).toEqual([0xaa, 0xbb, 0xcc, 0xdd]);
    expect(dv.getUint16(16, false)).toBe(4242);
    expect(dv.getUint16(18, false)).toBe(777);
    expect(dv.getUint16(20, false)).toBe(crc16Ccitt(p, 0, 20));
  });

  test("rejects a targetNodeId that is not 4 bytes", () => {
    expect(() => encodeFoundPayload(fields({ targetNodeId: new Uint8Array(3) }))).toThrow();
  });
});

describe("verifyFoundFrame", () => {
  test("round-trips a built frame", async () => {
    const frame = await buildFoundFrame(fields(), sk);
    expect(frame.length).toBe(FOUND_FRAME_LEN);
    const res = await verifyFoundFrame(frame);
    if (!res.ok) throw new Error(res.reason);
    expect(res.found.lat).toBeCloseTo(10.4003, 6);
    expect(res.found.lon).toBeCloseTo(-66.9, 6);
    expect(res.found.targetNodeIdHex).toBe("aabbccdd");
    expect(res.found.targetMsgId).toBe(4242);
    expect(res.found.foundMsgId).toBe(777);
    const pub = await ed.getPublicKeyAsync(sk);
    expect(res.found.finderPublicKeyHex).toBe(bytesToHex(pub));
    expect(res.found.finderNodeIdHex).toBe(bytesToHex(sha256(pub).subarray(0, 4)));
  });

  test("accepts the 119-byte on-wire form by stripping the leading TTL byte", async () => {
    const frame = await buildFoundFrame(fields(), sk);
    const onWire = new Uint8Array(frame.length + 1);
    onWire[0] = 5; // hop TTL
    onWire.set(frame, 1);
    const res = await verifyFoundFrame(onWire);
    expect(res.ok).toBe(true);
  });

  test("rejects a tampered payload (crc mismatch)", async () => {
    const frame = await buildFoundFrame(fields(), sk);
    frame[0] ^= 0xff;
    const res = await verifyFoundFrame(frame);
    expect(res.ok).toBe(false);
  });

  test("rejects a signature made without the domain prefix", async () => {
    const payload = encodeFoundPayload(fields());
    const pub = await ed.getPublicKeyAsync(sk);
    const badSig = await ed.signAsync(payload, sk); // bare payload — wrong domain
    const frame = new Uint8Array(FOUND_FRAME_LEN);
    frame.set(payload, 0);
    frame.set(pub, FOUND_PUBKEY_OFF);
    frame.set(badSig, FOUND_SIG_OFF);
    const res = await verifyFoundFrame(frame);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toBe("signature invalid");
  });

  test("rejects a frame with no GPS fix (0,0)", async () => {
    const frame = await buildFoundFrame(fields({ latE7: 0, lonE7: 0 }), sk);
    const res = await verifyFoundFrame(frame);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toBe("no gps fix");
  });

  test("rejects a wrong-length frame", async () => {
    const res = await verifyFoundFrame(new Uint8Array(50));
    expect(res.ok).toBe(false);
  });
});

describe("haversineMeters", () => {
  test("~33 m for a 0.0003° latitude offset", () => {
    const d = haversineMeters(10.4, -66.9, 10.4003, -66.9);
    expect(d).toBeGreaterThan(25);
    expect(d).toBeLessThan(45);
  });

  test("~1.1 km for a 0.01° latitude offset", () => {
    const d = haversineMeters(10.4, -66.9, 10.41, -66.9);
    expect(d).toBeGreaterThan(1000);
    expect(d).toBeLessThan(1250);
  });
});
```

- [ ] **Step 4: Run test to verify it fails**

Run: `bun test packages/shared/src/mesh/foundFrame.test.ts`
Expected: FAIL — cannot resolve `./foundFrame.js`.

- [ ] **Step 5: Implement `foundFrame.ts`**

Create `packages/shared/src/mesh/foundFrame.ts`:

```ts
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { crc16Ccitt } from "./crc16.js";
import {
  FOUND_FRAME_LEN,
  FOUND_ON_WIRE_LEN,
  FOUND_PAYLOAD_LEN,
  FOUND_PAYLOAD_OFF,
  FOUND_PUBKEY_OFF,
  FOUND_SIGN_DOMAIN,
  FOUND_SIG_OFF,
  MESH_PUBKEY_LEN,
  MESH_SIG_LEN,
} from "./constants.js";

/**
 * Found payload v1 ("Encontrado") — 22 bytes, big-endian, byte-identical with
 * net.guacamaya.proto.FoundPayload (Android). See constants.ts for the byte map.
 *
 * Signature is domain-separated: Ed25519 over UTF8(FOUND_SIGN_DOMAIN) || payload22
 * — never over the bare payload, so SOS/control/found signatures are never
 * interchangeable.
 */

export interface FoundPayloadFields {
  /** Finder latitude × 1e7 (int32). */
  latE7: number;
  /** Finder longitude × 1e7 (int32). */
  lonE7: number;
  /** Unix seconds. */
  tsUnix: number;
  /** SHA-256(victim pubkey)[0..3], 4 bytes. */
  targetNodeId: Uint8Array;
  targetMsgId: number;
  /** CSPRNG per found report (dedupe entropy). */
  foundMsgId: number;
}

export interface VerifiedFoundFrame extends FoundPayloadFields {
  lat: number;
  lon: number;
  targetNodeIdHex: string;
  finderPublicKeyHex: string;
  /** SHA-256(finder pubkey)[0..3] as hex. */
  finderNodeIdHex: string;
  signatureHex: string;
  payload: Uint8Array;
  /** Canonical 118-byte upload frame (TTL stripped). */
  frame: Uint8Array;
}

export type FoundFrameResult =
  | { ok: true; found: VerifiedFoundFrame }
  | { ok: false; reason: string };

function checkU16(value: number, name: string): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffff) throw new Error(`${name} out of u16 range`);
}

function checkU32(value: number, name: string): void {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) throw new Error(`${name} out of u32 range`);
}

function checkI32(value: number, name: string): void {
  if (!Number.isInteger(value) || value < -0x80000000 || value > 0x7fffffff) throw new Error(`${name} out of i32 range`);
}

/** Encodes the 22-byte found payload, computing the trailing CRC. Throws on invalid fields. */
export function encodeFoundPayload(fields: FoundPayloadFields): Uint8Array {
  if (fields.targetNodeId.length !== 4) throw new Error("targetNodeId must be 4 bytes");
  checkI32(fields.latE7, "latE7");
  checkI32(fields.lonE7, "lonE7");
  checkU32(fields.tsUnix, "tsUnix");
  checkU16(fields.targetMsgId, "targetMsgId");
  checkU16(fields.foundMsgId, "foundMsgId");
  const payload = new Uint8Array(FOUND_PAYLOAD_LEN);
  const dv = new DataView(payload.buffer);
  dv.setInt32(0, fields.latE7, false);
  dv.setInt32(4, fields.lonE7, false);
  dv.setUint32(8, fields.tsUnix, false);
  payload.set(fields.targetNodeId, 12);
  dv.setUint16(16, fields.targetMsgId, false);
  dv.setUint16(18, fields.foundMsgId, false);
  dv.setUint16(20, crc16Ccitt(payload, 0, 20), false);
  return payload;
}

/** The exact bytes the Ed25519 signature covers: UTF8 domain prefix + payload. */
export function foundSignBytes(payload: Uint8Array): Uint8Array {
  const domain = new TextEncoder().encode(FOUND_SIGN_DOMAIN);
  const out = new Uint8Array(domain.length + payload.length);
  out.set(domain, 0);
  out.set(payload, domain.length);
  return out;
}

/** Builds the canonical 118-byte upload frame: payload22 + pubkey32 + sig64. */
export async function buildFoundFrame(
  fields: FoundPayloadFields,
  privateKey: Uint8Array,
): Promise<Uint8Array> {
  const payload = encodeFoundPayload(fields);
  const pubkey = await ed.getPublicKeyAsync(privateKey);
  const sig = await ed.signAsync(foundSignBytes(payload), privateKey);
  const frame = new Uint8Array(FOUND_FRAME_LEN);
  frame.set(payload, FOUND_PAYLOAD_OFF);
  frame.set(pubkey, FOUND_PUBKEY_OFF);
  frame.set(sig, FOUND_SIG_OFF);
  return frame;
}

/**
 * Zero-trust verification cascade, cheapest first (mirrors the SOS/control
 * verifiers): size → CRC → GPS-fix sanity → Ed25519 (domain-prefixed).
 * Timestamp skew is intentionally omitted — a data mule may upload stale reports.
 * Target existence / proximity verdicts stay with the backend caller.
 */
export async function verifyFoundFrame(bytes: Uint8Array): Promise<FoundFrameResult> {
  let frame = bytes;
  if (frame.length === FOUND_ON_WIRE_LEN) frame = frame.subarray(1);
  if (frame.length !== FOUND_FRAME_LEN) {
    return { ok: false, reason: `bad found frame length ${bytes.length} (need ${FOUND_FRAME_LEN})` };
  }

  const payload = frame.subarray(FOUND_PAYLOAD_OFF, FOUND_PAYLOAD_OFF + FOUND_PAYLOAD_LEN);
  const pubkey = frame.subarray(FOUND_PUBKEY_OFF, FOUND_PUBKEY_OFF + MESH_PUBKEY_LEN);
  const sig = frame.subarray(FOUND_SIG_OFF, FOUND_SIG_OFF + MESH_SIG_LEN);
  const dv = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);

  if (dv.getUint16(20, false) !== crc16Ccitt(payload, 0, 20)) {
    return { ok: false, reason: "crc mismatch" };
  }

  const latE7 = dv.getInt32(0, false);
  const lonE7 = dv.getInt32(4, false);
  const lat = latE7 / 1e7;
  const lon = lonE7 / 1e7;
  const hasFix =
    lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(latE7 === 0 && lonE7 === 0);
  if (!hasFix) {
    // A found report is a proximity claim; without the finder's fix there is
    // nothing to validate against, so the frame is useless — reject it.
    return { ok: false, reason: "no gps fix" };
  }

  let sigValid = false;
  try {
    sigValid = await ed.verifyAsync(sig, foundSignBytes(payload), pubkey);
  } catch {
    sigValid = false;
  }
  if (!sigValid) return { ok: false, reason: "signature invalid" };

  const targetNodeId = payload.subarray(12, 16);
  return {
    ok: true,
    found: {
      latE7,
      lonE7,
      lat,
      lon,
      tsUnix: dv.getUint32(8, false),
      targetNodeId: new Uint8Array(targetNodeId),
      targetNodeIdHex: bytesToHex(targetNodeId),
      targetMsgId: dv.getUint16(16, false),
      foundMsgId: dv.getUint16(18, false),
      finderPublicKeyHex: bytesToHex(pubkey),
      finderNodeIdHex: bytesToHex(sha256(pubkey).subarray(0, 4)),
      signatureHex: bytesToHex(sig),
      payload: new Uint8Array(payload),
      frame: new Uint8Array(frame),
    },
  };
}
```

- [ ] **Step 6: Export the new modules**

In `packages/shared/src/mesh/index.ts` add at the end:

```ts
export * from "./foundFrame.js";
```

In `packages/shared/src/index.ts` add after the `export * from "./merge.js";` line:

```ts
export * from "./geo.js";
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `bun test packages/shared/src/mesh/foundFrame.test.ts`
Expected: PASS (all tests green).

Run: `bun test` — expected: full suite still green (no existing test touched).

- [ ] **Step 8: Commit**

```bash
git add packages/shared/src/mesh/constants.ts packages/shared/src/geo.ts packages/shared/src/mesh/foundFrame.ts packages/shared/src/mesh/index.ts packages/shared/src/index.ts packages/shared/src/mesh/foundFrame.test.ts
git commit -m "feat(shared): found-frame v1 wire format + haversine helper"
```

---

### Task 2: Backend — `processFoundFrames` with proximity verdict

**Files:**
- Modify: `backend/src/security/config.ts` (add `foundProximityMeters`)
- Create: `backend/src/found/processFound.ts`
- Test: `backend/src/found/processFound.test.ts`
- Modify: `backend/.env.example` (document the new env var)

**Interfaces:**
- Consumes (from Task 1, via `@guacamaya/shared`): `verifyFoundFrame`, `haversineMeters`, `buildFoundFrame` (tests only).
- Produces (used by Task 3):
  - `FOUND_CHANNEL = "encontrado"` (const string)
  - `interface FoundTargetSos { recordId: string; lat: number; lon: number }`
  - `interface FoundDeps { proximityNearMeters: number; findTargetSos(targetNodeIdHex: string, targetMsgId: number): Promise<FoundTargetSos | null>; addRecord(record: ChannelRecord): Promise<boolean>; broadcast(record: ChannelRecord): void }`
  - `interface FoundResultEntry { id: string; targetRecordId: string; distanceMeters: number; proximity: "near" | "far"; verdict: "likely_found" | "possible_false_positive" }`
  - `interface ProcessFoundSummary { accepted: number; duplicate: number; rejected: number; reasons: Record<string, number>; results: FoundResultEntry[] }`
  - `processFoundFrames(frames: string[], deps: FoundDeps): Promise<ProcessFoundSummary>`

- [ ] **Step 1: Add the config knob**

In `backend/src/security/config.ts`, directly below the line `maxFrameB64Length: boundedNumber("MAX_FRAME_B64_LENGTH", 256, 64, 4096, true),` add:

```ts
  /** "Encontrado" proximity gate: finder within this many meters of the SOS's
   * last reported point → likely_found; further → possible_false_positive. */
  foundProximityMeters: boundedNumber("FOUND_PROXIMITY_METERS", 100, 1, 100_000),
```

In `backend/.env.example`, append at the end:

```
# "Encontrado" (found) reports — proximity gate in meters. A finder within this
# distance of the SOS's last reported point is "near / likely_found"; further is
# "far / possible_false_positive".
FOUND_PROXIMITY_METERS=100
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/found/processFound.test.ts`:

```ts
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `bun test backend/src/found/processFound.test.ts`
Expected: FAIL — cannot resolve `./processFound.js`.

- [ ] **Step 4: Implement `processFound.ts`**

Create `backend/src/found/processFound.ts`:

```ts
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import {
  type ChannelRecord,
  haversineMeters,
  verifyFoundFrame,
} from "@guacamaya/shared";
import { isValidFrameB64 } from "../security/validation.js";

/** Community channel that holds "Encontrado" (found) reports. */
export const FOUND_CHANNEL = "encontrado";

/** The target SOS's last reported point, resolved by the caller's data layer. */
export interface FoundTargetSos {
  recordId: string;
  lat: number;
  lon: number;
}

export interface FoundDeps {
  /** Meters; at/under → near/likely_found, over → far/possible_false_positive. */
  proximityNearMeters: number;
  /** Latest SOS record for (targetNodeId, targetMsgId), or null when none with a GPS fix exists. */
  findTargetSos(targetNodeIdHex: string, targetMsgId: number): Promise<FoundTargetSos | null>;
  /** Persist; returns false when the id already exists (dedupe). */
  addRecord(record: ChannelRecord): Promise<boolean>;
  /** WS fan-out for freshly persisted records only. */
  broadcast(record: ChannelRecord): void;
}

export interface FoundResultEntry {
  /** ChannelRecord id of the found report (SHA-256(payload22) hex). */
  id: string;
  targetRecordId: string;
  distanceMeters: number;
  proximity: "near" | "far";
  verdict: "likely_found" | "possible_false_positive";
}

export interface ProcessFoundSummary {
  accepted: number;
  duplicate: number;
  rejected: number;
  reasons: Record<string, number>;
  results: FoundResultEntry[];
}

/**
 * Zero-trust "Encontrado" ingestion: every frame is re-verified (CRC + Ed25519
 * over the found domain) before anything is persisted, the target SOS must
 * already exist server-side, and the proximity verdict is computed from the
 * finder's *signed* GPS position against the SOS's last reported point — the
 * client never supplies a verdict or a record id.
 */
export async function processFoundFrames(
  frames: string[],
  deps: FoundDeps,
): Promise<ProcessFoundSummary> {
  const summary: ProcessFoundSummary = {
    accepted: 0,
    duplicate: 0,
    rejected: 0,
    reasons: {},
    results: [],
  };
  const reject = (reason: string) => {
    summary.rejected++;
    summary.reasons[reason] = (summary.reasons[reason] ?? 0) + 1;
  };

  for (const b64 of frames) {
    if (typeof b64 !== "string" || !isValidFrameB64(b64)) {
      reject("invalid frame string");
      continue;
    }
    const bytes = Uint8Array.from(Buffer.from(b64, "base64"));

    const res = await verifyFoundFrame(bytes);
    if (!res.ok) {
      reject(res.reason);
      continue;
    }
    const f = res.found;

    const target = await deps.findTargetSos(f.targetNodeIdHex, f.targetMsgId);
    if (!target) {
      reject("unknown target sos");
      continue;
    }

    const distanceMeters = Math.round(haversineMeters(f.lat, f.lon, target.lat, target.lon));
    const near = distanceMeters <= deps.proximityNearMeters;
    const proximity = near ? ("near" as const) : ("far" as const);
    const verdict = near ? ("likely_found" as const) : ("possible_false_positive" as const);

    const record: ChannelRecord = {
      id: bytesToHex(sha256(f.payload)),
      channel: FOUND_CHANNEL,
      timestamp: f.tsUnix * 1000,
      ttl: 0,
      author: `device-${f.finderPublicKeyHex}`,
      verified: false,
      payload: {
        source: "mesh-found",
        finderNodeId: f.finderNodeIdHex,
        foundMsgId: f.foundMsgId,
        tsUnix: f.tsUnix,
        lat: f.lat,
        lon: f.lon,
        targetNodeId: f.targetNodeIdHex,
        targetMsgId: f.targetMsgId,
        targetRecordId: target.recordId,
        distanceMeters,
        proximity,
        verdict,
        frameB64: Buffer.from(f.frame).toString("base64"),
      },
      sig: f.signatureHex,
    };

    const added = await deps.addRecord(record);
    if (added) {
      summary.accepted++;
      deps.broadcast(record);
    } else {
      summary.duplicate++;
    }
    // The verdict is returned even on duplicate — the mule uploading the frame
    // is usually the finder's own phone, and it needs the answer both times.
    summary.results.push({
      id: record.id,
      targetRecordId: target.recordId,
      distanceMeters,
      proximity,
      verdict,
    });
  }

  return summary;
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bun test backend/src/found/processFound.test.ts`
Expected: PASS.

- [ ] **Step 6: Type-check the backend**

Run: `bun run --cwd backend tsc --noEmit`
Expected: no errors.

- [ ] **Step 7: Commit**

```bash
git add backend/src/found/processFound.ts backend/src/found/processFound.test.ts backend/src/security/config.ts backend/.env.example
git commit -m "feat(backend): zero-trust Encontrado processing with proximity verdict"
```

---

### Task 3: Backend — wire `foundFrames` into `POST /ingest` and register the channel

**Files:**
- Modify: `backend/src/security/validation.ts` (add `"encontrado"` channel)
- Modify: `backend/src/channels/routes.ts` (accept + process `foundFrames`, list the channel)

**Interfaces:**
- Consumes: `processFoundFrames`, `FOUND_CHANNEL`, `FoundDeps`, `ProcessFoundSummary` from Task 2; existing `channelsRepo`, `broadcastRecord`, `securityConfig`, `validateIngestBatch`, `SOS_CHANNEL`.
- Produces: `POST /ingest` accepts `{ foundFrames?: string[] }` and returns four new response fields: `foundAccepted: number`, `foundDuplicate: number`, `foundRejected: number`, `foundReasons: Record<string, number>`, `foundResults: FoundResultEntry[]`. `GET /channels` lists `encontrado`; `GET /channels/encontrado/records` works.

- [ ] **Step 1: Register the channel id**

In `backend/src/security/validation.ts`, change the `VALID_CHANNEL_IDS` set:

```ts
const VALID_CHANNEL_IDS = new Set([
  "alertas",
  "refugios",
  "ayuda-medica",
  "estoy-bien",
  "solicito-ayuda",
  "resuelto",
  "encontrado",
]);
```

- [ ] **Step 2: List the channel in `GET /channels`**

In `backend/src/channels/routes.ts`, inside the `fastify.get("/channels", ...)` array, add after the `solicito-ayuda` entry:

```ts
    { id: "encontrado", name: "Encontrado (Comunidad)", verifiedOnly: false },
```

- [ ] **Step 3: Import the found module**

In `backend/src/channels/routes.ts`, after the line `import { processControlFrames, mintControlFrames, type SosRef } from "../control/ingestControl.js";` add:

```ts
import {
  processFoundFrames,
  FOUND_CHANNEL,
  type FoundDeps,
  type ProcessFoundSummary,
} from "../found/processFound.js";
```

- [ ] **Step 4: Add the default deps factory**

In `backend/src/channels/routes.ts`, add this function at module level, directly above `async function checkOriginatorVeto(...)` near the bottom of the file:

```ts
/**
 * Production wiring for the "Encontrado" processor: the reference point is the
 * LATEST verified SOS record for (targetNodeId, targetMsgId) on solicito-ayuda.
 * payload.nodeId / payload.msgId are set by decodeAndVerifyFrame from the
 * signed frame, so the lookup key is authenticated, never client-supplied.
 */
function foundDeps(): FoundDeps {
  return {
    proximityNearMeters: securityConfig.foundProximityMeters,
    async findTargetSos(targetNodeIdHex, targetMsgId) {
      const records = await channelsRepo.getRecords(SOS_CHANNEL, 0);
      let best: ChannelRecord | null = null;
      for (const r of records) {
        const p = r.payload as { nodeId?: unknown; msgId?: unknown };
        if (p.nodeId === targetNodeIdHex && p.msgId === targetMsgId) {
          if (!best || r.timestamp > best.timestamp) best = r;
        }
      }
      if (!best) return null;
      const p = best.payload as { lat?: unknown; lon?: unknown };
      if (
        typeof p.lat !== "number" ||
        typeof p.lon !== "number" ||
        !Number.isFinite(p.lat) ||
        !Number.isFinite(p.lon) ||
        (p.lat === 0 && p.lon === 0)
      ) {
        return null; // SOS exists but carries no fix — nothing to validate against
      }
      return { recordId: best.id, lat: p.lat, lon: p.lon };
    },
    addRecord: (record) => channelsRepo.addRecord(record),
    broadcast: (record) => broadcastRecord(record),
  };
}
```

- [ ] **Step 5: Accept `foundFrames` in the `/ingest` body**

Still in `backend/src/channels/routes.ts`, make these four edits inside the `/ingest` route:

**(a)** Change the route generic and destructuring:

```ts
  fastify.post<{ Body: { frames?: unknown; controlFrames?: unknown; foundFrames?: unknown; rescuerPublicKeys?: unknown } }>(
    "/ingest",
    { config: { rateLimit: securityConfig.ingestRateLimit } },
    async (request, reply) => {
      const { frames, controlFrames, foundFrames } = request.body ?? {};
```

**(b)** Extend the batch validation block (replace the existing `framesValid`/`controlValid` guard):

```ts
      // A mule may carry SOS frames, control frames, found frames, or any mix.
      // At least one batch must be present and each is independently capped.
      const framesValid = validateIngestBatch(frames);
      const controlValid = validateIngestBatch(controlFrames);
      const foundValid = validateIngestBatch(foundFrames);
      if (!framesValid && !controlValid && !foundValid) {
        const tooMany =
          (Array.isArray(frames) && frames.length > securityConfig.maxIngestBatch) ||
          (Array.isArray(controlFrames) && controlFrames.length > securityConfig.maxIngestBatch) ||
          (Array.isArray(foundFrames) && foundFrames.length > securityConfig.maxIngestBatch);
        return reply.code(400).send({
          error: tooMany
            ? `Batch exceeds max ${securityConfig.maxIngestBatch} frames`
            : "Missing or invalid 'frames'/'controlFrames'/'foundFrames' array",
        });
      }

      const frameList: string[] = framesValid ? (frames as string[]) : [];
      const controlList: string[] = controlValid ? (controlFrames as string[]) : [];
      const foundList: string[] = foundValid ? (foundFrames as string[]) : [];
```

**(c)** Process found frames AFTER the SOS loop and the locations block (so a same-batch SOS provides the reference point). Insert directly after the `if (rejected > 0) { request.log.warn(...) }` block:

```ts
      // "Encontrado" reports: verify zero-trust, resolve the target SOS (which may
      // have arrived in this very batch — the SOS loop above already persisted it),
      // and compute the proximity verdict server-side.
      const foundSummary: ProcessFoundSummary = foundList.length > 0
        ? await processFoundFrames(foundList, foundDeps())
        : { accepted: 0, duplicate: 0, rejected: 0, reasons: {}, results: [] };
      if (foundSummary.rejected > 0) {
        request.log.warn(
          { rejected: foundSummary.rejected, reasons: foundSummary.reasons },
          "rejected unverifiable found frames",
        );
      }
```

**(d)** Extend the response object (add these fields to the returned object, after `controlFrames: minted.map((m) => m.frameB64),`):

```ts
        foundAccepted: foundSummary.accepted,
        foundDuplicate: foundSummary.duplicate,
        foundRejected: foundSummary.rejected,
        foundReasons: foundSummary.reasons,
        foundResults: foundSummary.results,
```

- [ ] **Step 6: Type-check and run the full suite**

Run: `bun run --cwd backend tsc --noEmit` — expected: no errors.
Run: `bun test` — expected: full suite green.

- [ ] **Step 7: End-to-end smoke check against the dev server**

Start the backend in the background: `bun run dev:backend` (leave it running; it listens on :3000 with in-memory stores).

Write this throwaway script to `/private/tmp/claude-501/-Users-carlosramirez-desarrollo-hackathons-build4venezuela-guacamaya-net/6fbe51d6-5a96-445d-bb13-6a860fdbd1f1/scratchpad/found-smoke.ts` and run it with `bun run <path>`:

```ts
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { buildFoundFrame, crc16Ccitt } from "@guacamaya/shared";

// 1. Build a valid SOS frame (victim at 10.4, -66.9) the same way frame.test.ts does.
const victimSk = new Uint8Array(32).fill(1);
const victimPk = await ed.getPublicKeyAsync(victimSk);
const nodeId = sha256(victimPk).subarray(0, 4);
const payload = new Uint8Array(22);
const dv = new DataView(payload.buffer);
dv.setInt32(0, 104_000_000, false); // lat 10.4
dv.setInt32(4, -669_000_000, false); // lon -66.9
dv.setUint32(8, Math.floor(Date.now() / 1000), false);
payload.set(nodeId, 12);
payload[16] = 0x02; // critical
payload[17] = 1; // distress
dv.setUint16(18, 100, false); // msgId
dv.setUint16(20, crc16Ccitt(payload, 0, 20), false);
const sig = await ed.signAsync(payload, victimSk);
const sosFrame = new Uint8Array(118);
sosFrame.set(payload, 0);
sosFrame.set(victimPk, 22);
sosFrame.set(sig, 54);

// 2. Build the found frame: finder ~33 m away marks (nodeId, msgId=100) as found.
const finderSk = new Uint8Array(32).fill(2);
const foundFrame = await buildFoundFrame(
  {
    latE7: 104_003_000,
    lonE7: -669_000_000,
    tsUnix: Math.floor(Date.now() / 1000),
    targetNodeId: new Uint8Array(nodeId),
    targetMsgId: 100,
    foundMsgId: 42,
  },
  finderSk,
);

const res = await fetch("http://localhost:3000/ingest", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    frames: [Buffer.from(sosFrame).toString("base64")],
    foundFrames: [Buffer.from(foundFrame).toString("base64")],
  }),
});
console.log(JSON.stringify(await res.json(), null, 2));
```

Expected output includes `"ingested": 1`, `"foundAccepted": 1`, and a `foundResults` entry with `"proximity": "near"`, `"verdict": "likely_found"`, `distanceMeters` ≈ 33. Then verify the channel read: `curl -s http://localhost:3000/channels/encontrado/records | head -c 600` should show one record with `"verdict":"likely_found"`. Stop the dev server afterwards.

- [ ] **Step 8: Commit**

```bash
git add backend/src/security/validation.ts backend/src/channels/routes.ts
git commit -m "feat(backend): accept foundFrames in POST /ingest and serve the encontrado channel"
```

---

### Task 4: Android — `proto/FoundPayload.kt` (wire mirror)

**Files:**
- Create: `android/app/src/main/kotlin/net/guacamaya/proto/FoundPayload.kt`
- Test: `android/app/src/test/kotlin/net/guacamaya/proto/FoundPayloadTest.kt`

**Interfaces:**
- Consumes: existing `Crc16.ccitt`, `Signer.derivePublicKey/signMessage/verifyMessage` (`net.guacamaya.crypto.Signer`), `ControlFrame.toHex` (`net.guacamaya.proto`).
- Produces (used by Tasks 6–7):
  - `object FoundFrame { PAYLOAD_LEN=22; PUBKEY_LEN=32; SIG_LEN=64; FRAME_LEN=118; ON_WIRE_LEN=119; PAYLOAD_OFF=0; PUBKEY_OFF=22; SIG_OFF=54; FOUND_SIGN_DOMAIN; fun signBytes(payload22: ByteArray): ByteArray }`
  - `data class FoundPayload(latE7: Int, lonE7: Int, tsUnix: Long, targetNodeId: ByteArray, targetMsgId: Int, foundMsgId: Int)` with `encode(): ByteArray` and `companion fun decode(bytes: ByteArray): FoundPayload`
  - `fun buildFoundFrame(payload: FoundPayload, privateKey: ByteArray): ByteArray` (returns the 118-byte upload frame)

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/kotlin/net/guacamaya/proto/FoundPayloadTest.kt`:

```kotlin
package net.guacamaya.proto

import net.guacamaya.crypto.Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FoundPayloadTest {

    private val targetNodeId = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    private fun payload() = FoundPayload(
        latE7 = 104_003_000,
        lonE7 = -669_000_000,
        tsUnix = 1_750_000_000L,
        targetNodeId = targetNodeId,
        targetMsgId = 4242,
        foundMsgId = 777,
    )

    @Test
    fun encodeUsesDocumentedBigEndianOffsets() {
        val bytes = payload().encode()
        assertEquals(FoundFrame.PAYLOAD_LEN, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(104_003_000, buf.getInt(0))
        assertEquals(-669_000_000, buf.getInt(4))
        assertEquals(1_750_000_000L, buf.getInt(8).toLong() and 0xFFFFFFFFL)
        assertArrayEquals(targetNodeId, bytes.copyOfRange(12, 16))
        assertEquals(4242, buf.getShort(16).toInt() and 0xFFFF)
        assertEquals(777, buf.getShort(18).toInt() and 0xFFFF)
        val crcStored = buf.getShort(20).toInt() and 0xFFFF
        assertEquals(Crc16.ccitt(bytes, 0, 20) and 0xFFFF, crcStored)
    }

    @Test
    fun decodeRoundTrips() {
        val decoded = FoundPayload.decode(payload().encode())
        assertEquals(payload(), decoded)
    }

    @Test
    fun buildFoundFrameSignsWithTheFoundDomain() {
        val sk = ByteArray(32) { 7 }
        val frame = buildFoundFrame(payload(), sk)
        assertEquals(FoundFrame.FRAME_LEN, frame.size)
        val payloadBytes = frame.copyOfRange(FoundFrame.PAYLOAD_OFF, FoundFrame.PAYLOAD_OFF + FoundFrame.PAYLOAD_LEN)
        val pubkey = frame.copyOfRange(FoundFrame.PUBKEY_OFF, FoundFrame.PUBKEY_OFF + FoundFrame.PUBKEY_LEN)
        val sig = frame.copyOfRange(FoundFrame.SIG_OFF, FoundFrame.SIG_OFF + FoundFrame.SIG_LEN)
        assertArrayEquals(Signer.derivePublicKey(sk), pubkey)
        // Signature covers the domain-prefixed bytes, not the bare payload.
        assertTrue(Signer.verifyMessage(pubkey, FoundFrame.signBytes(payloadBytes), sig))
        assertTrue(!Signer.verifyMessage(pubkey, payloadBytes, sig))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortTargetNodeId() {
        FoundPayload(0, 0, 0L, ByteArray(3), 1, 1)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests "net.guacamaya.proto.FoundPayloadTest"`
Expected: compilation FAILURE — `FoundPayload` unresolved.

- [ ] **Step 3: Implement `FoundPayload.kt`**

Create `android/app/src/main/kotlin/net/guacamaya/proto/FoundPayload.kt`:

```kotlin
package net.guacamaya.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.guacamaya.crypto.Signer

/**
 * Found frame v1 ("Encontrado") — a finder marks an SOS victim as found, carried
 * to the backend as `foundFrames[]` in POST /ingest.
 *
 * Same envelope as the SOS frame: 22 B payload + 32 B pubkey + 64 B signature.
 * Byte-identical with `packages/shared/src/mesh/foundFrame.ts` on the backend.
 *
 *   off 0..3   latE7         int32 — finder latitude × 1e7
 *   off 4..7   lonE7         int32 — finder longitude × 1e7
 *   off 8..11  tsUnix        u32 unix seconds
 *   off 12..15 targetNodeId  SHA-256(victim pubkey)[0..3]
 *   off 16..17 targetMsgId   u16 — msgId of the SOS session being marked
 *   off 18..19 foundMsgId    u16 — CSPRNG per found report (dedupe entropy)
 *   off 20..21 crc           CRC16-CCITT over bytes 0..19
 *
 * Signature is domain-separated: Ed25519 over UTF8(FOUND_SIGN_DOMAIN) || payload22
 * — never over the bare payload, so SOS/control/found signatures are never
 * interchangeable.
 */
object FoundFrame {
    const val PAYLOAD_LEN = 22
    const val PUBKEY_LEN = 32
    const val SIG_LEN = 64
    const val FRAME_LEN = PAYLOAD_LEN + PUBKEY_LEN + SIG_LEN // 118 (upload, TTL stripped)
    const val ON_WIRE_LEN = FRAME_LEN + 1                    // 119 (leading hop TTL)

    const val PAYLOAD_OFF = 0
    const val PUBKEY_OFF = PAYLOAD_OFF + PAYLOAD_LEN         // 22
    const val SIG_OFF = PUBKEY_OFF + PUBKEY_LEN              // 54

    /** Mirrored verbatim in packages/shared/src/mesh/constants.ts. */
    const val FOUND_SIGN_DOMAIN = "guacamaya.found-frame.v1\n"

    /** The exact bytes the Ed25519 signature covers: UTF8 domain prefix + payload. */
    fun signBytes(payload22: ByteArray): ByteArray {
        val domain = FOUND_SIGN_DOMAIN.toByteArray(Charsets.UTF_8)
        val out = ByteArray(domain.size + payload22.size)
        System.arraycopy(domain, 0, out, 0, domain.size)
        System.arraycopy(payload22, 0, out, domain.size, payload22.size)
        return out
    }
}

/**
 * Encodable found payload. [encode] throws on invalid fields — mirrors
 * `encodeFoundPayload` on the backend. The caller appends pubkey + signature to
 * build the 118-byte frame (see [buildFoundFrame]).
 */
data class FoundPayload(
    val latE7: Int,
    val lonE7: Int,
    val tsUnix: Long,          // u32 unix seconds
    /** SHA-256(victim pubkey)[0..3], exactly 4 bytes. */
    val targetNodeId: ByteArray,
    val targetMsgId: Int,      // u16
    val foundMsgId: Int,       // u16
) {
    init {
        require(targetNodeId.size == 4) { "targetNodeId must be 4 bytes" }
        require(tsUnix in 0..0xFFFFFFFFL) { "tsUnix out of u32 range" }
        require(targetMsgId in 0..0xFFFF) { "targetMsgId out of u16 range" }
        require(foundMsgId in 0..0xFFFF) { "foundMsgId out of u16 range" }
    }

    /** Encode to a fresh 22-byte big-endian array with the trailing CRC16 filled in. */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(FoundFrame.PAYLOAD_LEN).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putInt(tsUnix.toInt())
        buf.put(targetNodeId)
        buf.putShort(targetMsgId.toShort())
        buf.putShort(foundMsgId.toShort())
        val withoutCrc = buf.array().copyOfRange(0, FoundFrame.PAYLOAD_LEN - 2)
        val crc = Crc16.ccitt(withoutCrc)
        buf.putShort(FoundFrame.PAYLOAD_LEN - 2, crc.toShort())
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FoundPayload) return false
        return latE7 == other.latE7 &&
            lonE7 == other.lonE7 &&
            tsUnix == other.tsUnix &&
            targetNodeId.contentEquals(other.targetNodeId) &&
            targetMsgId == other.targetMsgId &&
            foundMsgId == other.foundMsgId
    }

    override fun hashCode(): Int {
        var r = latE7
        r = 31 * r + lonE7
        r = 31 * r + tsUnix.hashCode()
        r = 31 * r + targetNodeId.contentHashCode()
        r = 31 * r + targetMsgId
        r = 31 * r + foundMsgId
        return r
    }

    companion object {
        /** Decode a 22-byte array; throws on size or CRC failure. Does not verify the signature. */
        fun decode(bytes: ByteArray): FoundPayload {
            require(bytes.size == FoundFrame.PAYLOAD_LEN) {
                "found payload must be ${FoundFrame.PAYLOAD_LEN} bytes, got ${bytes.size}"
            }
            val crcStored = ((bytes[FoundFrame.PAYLOAD_LEN - 2].toInt() and 0xFF) shl 8) or
                (bytes[FoundFrame.PAYLOAD_LEN - 1].toInt() and 0xFF)
            val crcComputed = Crc16.ccitt(bytes, 0, FoundFrame.PAYLOAD_LEN - 2) and 0xFFFF
            require(crcStored == crcComputed) { "crc mismatch" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val latE7 = buf.getInt(0)
            val lonE7 = buf.getInt(4)
            val tsUnix = buf.getInt(8).toLong() and 0xFFFFFFFFL
            val targetNodeId = bytes.copyOfRange(12, 16)
            val targetMsgId = buf.getShort(16).toInt() and 0xFFFF
            val foundMsgId = buf.getShort(18).toInt() and 0xFFFF
            return FoundPayload(latE7, lonE7, tsUnix, targetNodeId, targetMsgId, foundMsgId)
        }
    }
}

/**
 * Assemble the canonical 118-byte upload frame: payload22 + pubkey32 + sig64. The
 * signature covers [FoundFrame.signBytes] of the encoded payload (domain-prefixed).
 */
fun buildFoundFrame(payload: FoundPayload, privateKey: ByteArray): ByteArray {
    require(privateKey.size == FoundFrame.PUBKEY_LEN) {
        "private key must be ${FoundFrame.PUBKEY_LEN} bytes, got ${privateKey.size}"
    }
    val payloadBytes = payload.encode()
    val pubkey = Signer.derivePublicKey(privateKey)
    val sig = Signer.signMessage(privateKey, FoundFrame.signBytes(payloadBytes))
    val frame = ByteArray(FoundFrame.FRAME_LEN)
    System.arraycopy(payloadBytes, 0, frame, FoundFrame.PAYLOAD_OFF, FoundFrame.PAYLOAD_LEN)
    System.arraycopy(pubkey, 0, frame, FoundFrame.PUBKEY_OFF, FoundFrame.PUBKEY_LEN)
    System.arraycopy(sig, 0, frame, FoundFrame.SIG_OFF, FoundFrame.SIG_LEN)
    return frame
}
```

- [ ] **Step 4: Run test to verify it passes**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests "net.guacamaya.proto.FoundPayloadTest"`
Expected: BUILD SUCCESSFUL, all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/net/guacamaya/proto/FoundPayload.kt android/app/src/test/kotlin/net/guacamaya/proto/FoundPayloadTest.kt
git commit -m "feat(android): FoundPayload wire mirror with domain-separated signature"
```

---

### Task 5: Android — Room queue `outbound_found` (migration v9 → v10)

**Files:**
- Modify: `android/app/src/main/kotlin/net/guacamaya/mesh/MessageStore.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Tasks 6–7):
  - `data class OutboundFoundEntity(signatureHex: String, frame: ByteArray, uploaded: Boolean = false, queuedAt: Long)` (table `outbound_found`)
  - DAO methods on `MessageDao`: `insertOutboundFound(entity): Long`, `selectUploadableFound(limit: Int): List<OutboundFoundEntity>`, `markFoundUploaded(sigs: List<String>)`, `countUploadableFound(): Int`

- [ ] **Step 1: Add the entity**

In `android/app/src/main/kotlin/net/guacamaya/mesh/MessageStore.kt`, directly below the `OutboundControlEntity` class (after its closing brace at ~line 210), add:

```kotlin
/**
 * A locally-authored "Encontrado" (found) frame queued for data-mule upload to
 * the backend (`foundFrames[]` in POST /ingest). Keyed by the 64-byte Ed25519
 * signature hex so the same report is queued exactly once. [frame] is the
 * canonical 118 B upload blob (payload22 ‖ pubkey32 ‖ sig64) built by
 * net.guacamaya.proto.buildFoundFrame.
 */
@Entity(
    tableName = "outbound_found",
    indices = [Index(value = ["queued_at"])],
)
data class OutboundFoundEntity(
    /** 64-byte Ed25519 signature hex (128 chars). The dedup key. */
    @PrimaryKey @ColumnInfo(name = "signature_hex") val signatureHex: String,
    /** Canonical 118 B upload frame (payload22 + pubkey32 + sig64). */
    @ColumnInfo(name = "frame") val frame: ByteArray,
    /** Set once the backend accepted/duplicated the frame in `foundFrames[]`. */
    @ColumnInfo(name = "uploaded", defaultValue = "0") val uploaded: Boolean = false,
    @ColumnInfo(name = "queued_at") val queuedAt: Long,
) {
    // Room needs equals/hashCode for ByteArray fields.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
```

- [ ] **Step 2: Add the DAO methods**

In the `MessageDao` interface, directly below the `countUploadableControl()` method, add:

```kotlin
    // ── Outbound found frames: mule-upload queue ("Encontrado" → backend) ────────

    /** Queue a locally-authored found frame. IGNORE on the PK dedupes by signature. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboundFound(entity: OutboundFoundEntity): Long

    /** Oldest-first batch of found frames not yet uploaded, drained alongside SOS
     *  frames in the same `/ingest` call (the backend's `foundFrames[]` field). */
    @Query("SELECT * FROM outbound_found WHERE uploaded = 0 ORDER BY queued_at ASC LIMIT :limit")
    suspend fun selectUploadableFound(limit: Int): List<OutboundFoundEntity>

    @Query("UPDATE outbound_found SET uploaded = 1 WHERE signature_hex IN (:sigs)")
    suspend fun markFoundUploaded(sigs: List<String>)

    @Query("SELECT COUNT(*) FROM outbound_found WHERE uploaded = 0")
    suspend fun countUploadableFound(): Int
```

- [ ] **Step 3: Add the migration and bump the DB version**

Below `MIGRATION_8_9`, add:

```kotlin
/**
 * v9 → v10: outbound queue for "Encontrado" (found) frames awaiting data-mule
 * upload to the backend's `foundFrames[]` ingest path. Independent of signed SOS
 * storage and of the control tables, so the protocol's upload/relay semantics
 * are unchanged.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outbound_found (" +
                "signature_hex TEXT NOT NULL PRIMARY KEY, " +
                "frame BLOB NOT NULL, " +
                "uploaded INTEGER NOT NULL DEFAULT 0, queued_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_outbound_found_queued_at ON outbound_found (queued_at)")
    }
}
```

Then in the `@Database` annotation: add `OutboundFoundEntity::class,` to the `entities` list (after `OutboundControlEntity::class,`) and change `version = 9` to `version = 10`. In the `Room.databaseBuilder` chain, change the `.addMigrations(...)` call to include the new migration:

```kotlin
.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
```

- [ ] **Step 4: Verify it compiles**

Run (from `android/`): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Room validates entity/DAO consistency at compile time).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/net/guacamaya/mesh/MessageStore.kt
git commit -m "feat(android): outbound_found Room queue for Encontrado mule upload"
```

---

### Task 6: Android — upload pipeline (`IngestApi` / `IngestClient` / `IngestRepository` / worker)

**Files:**
- Modify: `android/app/src/main/kotlin/net/guacamaya/ingest/IngestClient.kt`
- Modify: `android/app/src/main/kotlin/net/guacamaya/ingest/IngestRepository.kt`
- Modify: `android/app/src/main/kotlin/net/guacamaya/ingest/IngestUploadWorker.kt`
- Test: `android/app/src/test/kotlin/net/guacamaya/ingest/IngestRepositoryTest.kt` (update fakes + add a case)

**Interfaces:**
- Consumes: `OutboundFoundEntity` + DAO methods from Task 5; backend response fields from Task 3 (`foundAccepted`, `foundDuplicate`, `foundRejected`, `foundResults[]` with `{ id, targetRecordId, distanceMeters, proximity, verdict }`).
- Produces (used by Task 7): nothing new for the UI — this task closes the queue-to-backend loop.
  - `IngestApi.upload(frames, controlFrames, foundFrames)` — 3-list signature.
  - `IngestResult` gains `foundAccepted/foundDuplicate/foundRejected: Int` and `foundResults: List<FoundVerdict>`; `data class FoundVerdict(id: String, targetRecordId: String, distanceMeters: Int, proximity: String, verdict: String)`.
  - `IngestRepository.UploadSummary` gains `foundFramesSent/foundAccepted/foundDuplicate/foundRejected: Int` and `foundResults: List<FoundVerdict>`.

- [ ] **Step 1: Extend `IngestClient.kt`**

Apply these edits to `android/app/src/main/kotlin/net/guacamaya/ingest/IngestClient.kt`:

**(a)** Add to `IngestResult` (after `controlFrames: List<String> = emptyList(),`):

```kotlin
    val foundAccepted: Int = 0,
    val foundDuplicate: Int = 0,
    val foundRejected: Int = 0,
    val foundResults: List<FoundVerdict> = emptyList(),
```

**(b)** Below the `MuleCrl` data class, add:

```kotlin
/** Backend proximity verdict for one uploaded "Encontrado" frame. */
data class FoundVerdict(
    val id: String,
    val targetRecordId: String,
    val distanceMeters: Int,
    /** "near" | "far" */
    val proximity: String,
    /** "likely_found" | "possible_false_positive" */
    val verdict: String,
)
```

**(c)** Change the `IngestApi` interface method to:

```kotlin
    /**
     * POST a batch of base64 SOS [frames], (optional) base64 [controlFrames]
     * (self-cancels) and (optional) base64 [foundFrames] ("Encontrado" reports).
     * Any list may be empty; the backend accepts a batch with at least one
     * non-empty side. Throws [IOException] on transport failure.
     */
    suspend fun upload(
        frames: List<String>,
        controlFrames: List<String>,
        foundFrames: List<String>,
    ): IngestResult
```

**(d)** Change `IngestClient.upload` signature to match (`override suspend fun upload(frames: List<String>, controlFrames: List<String>, foundFrames: List<String>): IngestResult`), pass `foundFrames` to `buildBody`, and add the found fields to the returned `IngestResult` (after `controlFrames = ...`):

```kotlin
                foundAccepted = json.optInt("foundAccepted", 0),
                foundDuplicate = json.optInt("foundDuplicate", 0),
                foundRejected = json.optInt("foundRejected", 0),
                foundResults = json.optJSONArray("foundResults")?.let { arr ->
                    List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        FoundVerdict(
                            id = o.optString("id"),
                            targetRecordId = o.optString("targetRecordId"),
                            distanceMeters = o.optInt("distanceMeters"),
                            proximity = o.optString("proximity"),
                            verdict = o.optString("verdict"),
                        )
                    }
                } ?: emptyList(),
```

**(e)** Change `buildBody` to:

```kotlin
    private fun buildBody(
        frames: List<String>,
        controlFrames: List<String>,
        foundFrames: List<String>,
    ): String {
        val obj = JSONObject()
        if (frames.isNotEmpty()) obj.put("frames", JSONArray(frames))
        if (controlFrames.isNotEmpty()) obj.put("controlFrames", JSONArray(controlFrames))
        if (foundFrames.isNotEmpty()) obj.put("foundFrames", JSONArray(foundFrames))
        return obj.toString()
    }
```

- [ ] **Step 2: Extend `IngestRepository.kt`**

In `android/app/src/main/kotlin/net/guacamaya/ingest/IngestRepository.kt`:

**(a)** Add fields to `UploadSummary` (after `controlRejected: Int = 0,`):

```kotlin
        val foundFramesSent: Int = 0,
        val foundAccepted: Int = 0,
        val foundDuplicate: Int = 0,
        val foundRejected: Int = 0,
        /** Backend proximity verdicts for the "Encontrado" frames this run uploaded. */
        val foundResults: List<FoundVerdict> = emptyList(),
```

**(b)** Rework `uploadPending` so the found queue drains alongside the other two. The existing method builds `UploadSummary` in four places; to keep them in sync, replace the whole body of `uploadPending` with this version (same logic plus the found side, with the repeated construction factored into a local helper):

```kotlin
    suspend fun uploadPending(maxBatches: Int = DEFAULT_MAX_BATCHES): UploadSummary {
        var batches = 0
        var framesSent = 0
        var controlFramesSent = 0
        var foundFramesSent = 0
        var ingested = 0
        var duplicate = 0
        var rejected = 0
        var controlAccepted = 0
        var controlDuplicate = 0
        var controlRejected = 0
        var foundAccepted = 0
        var foundDuplicate = 0
        var foundRejected = 0
        val foundResults = mutableListOf<FoundVerdict>()
        val minted = mutableListOf<String>()
        var crl: MuleCrl? = null

        fun summary(outcome: Outcome) = UploadSummary(
            outcome = outcome,
            batches = batches,
            framesSent = framesSent,
            controlFramesSent = controlFramesSent,
            foundFramesSent = foundFramesSent,
            ingested = ingested,
            duplicate = duplicate,
            rejected = rejected,
            controlAccepted = controlAccepted,
            controlDuplicate = controlDuplicate,
            controlRejected = controlRejected,
            foundAccepted = foundAccepted,
            foundDuplicate = foundDuplicate,
            foundRejected = foundRejected,
            foundResults = foundResults.toList(),
            mintedControlFrames = minted.toList(),
            certificateRevocationList = crl,
        )

        repeat(maxBatches) {
            val batch = dao.selectUploadable(batchSize)
            val controlBatch = dao.selectUploadableControl(batchSize)
            val foundBatch = dao.selectUploadableFound(batchSize)
            if (batch.isEmpty() && controlBatch.isEmpty() && foundBatch.isEmpty()) {
                return summary(Outcome.SUCCESS)
            }

            val frames = batch.map { IngestFrame.toBase64(it.payloadRaw, it.pubkey, it.sig) }
            // Control/found frames are stored as their canonical 118 B blob, so they
            // only need base64 — same std encoder IngestFrame uses, keeping this
            // class Android-free.
            val controlFrames = controlBatch.map { Base64.getEncoder().encodeToString(it.frame) }
            val foundFrames = foundBatch.map { Base64.getEncoder().encodeToString(it.frame) }
            val result = try {
                api.upload(frames, controlFrames, foundFrames)
            } catch (_: IOException) {
                return summary(Outcome.RETRY)
            }
            if (!result.ok) {
                return summary(Outcome.RETRY)
            }

            dao.markUploaded(batch.map { it.id })
            dao.markControlUploaded(controlBatch.map { it.signatureHex })
            dao.markFoundUploaded(foundBatch.map { it.signatureHex })
            batches++
            framesSent += frames.size
            controlFramesSent += controlFrames.size
            foundFramesSent += foundFrames.size
            ingested += result.ingested
            duplicate += result.duplicate
            rejected += result.rejected
            controlAccepted += result.controlAccepted
            controlDuplicate += result.controlDuplicate
            controlRejected += result.controlRejected
            foundAccepted += result.foundAccepted
            foundDuplicate += result.foundDuplicate
            foundRejected += result.foundRejected
            foundResults += result.foundResults
            minted += result.controlFrames
            result.certificateRevocationList?.let { candidate ->
                if (crl == null || candidate.sequence > crl!!.sequence) crl = candidate
            }
        }

        return summary(Outcome.SUCCESS)
    }
```

Also update the class KDoc's first line to mention found frames: change "pull un-uploaded verified frames in batches" to "pull un-uploaded verified frames (SOS, self-cancel control, and Encontrado reports) in batches".

- [ ] **Step 3: Update the worker log line**

In `android/app/src/main/kotlin/net/guacamaya/ingest/IngestUploadWorker.kt`, extend the `Log.i(TAG, "ingest run ...")` message: after `"ctrlDup=${summary.controlDuplicate} ctrlRej=${summary.controlRejected}"` append `+ " foundSent=${summary.foundFramesSent} foundOk=${summary.foundAccepted} foundRej=${summary.foundRejected}"`. Then, after the existing `if (summary.controlRejected > 0) { ... }` block, add:

```kotlin
        for (v in summary.foundResults) {
            Log.i(TAG, "found verdict target=${v.targetRecordId.take(12)} d=${v.distanceMeters}m ${v.proximity}/${v.verdict}")
        }
```

- [ ] **Step 4: Update the repository test and add a found case**

In `android/app/src/test/kotlin/net/guacamaya/ingest/IngestRepositoryTest.kt`:

1. The fake `IngestApi` (around line 101) must change its `upload` signature to `override suspend fun upload(frames: List<String>, controlFrames: List<String>, foundFrames: List<String>): IngestResult` — keep its existing behavior, and record `foundFrames` the same way it records the other lists (add a `val uploadedFound = mutableListOf<List<String>>()` alongside the existing recording lists if the fake records calls).
2. The fake `MessageDao` in that file must implement the three new methods. Add an in-memory list:

```kotlin
    val outboundFound = mutableListOf<OutboundFoundEntity>()

    override suspend fun insertOutboundFound(entity: OutboundFoundEntity): Long {
        if (outboundFound.any { it.signatureHex == entity.signatureHex }) return -1
        outboundFound.add(entity)
        return outboundFound.size.toLong()
    }

    override suspend fun selectUploadableFound(limit: Int): List<OutboundFoundEntity> =
        outboundFound.filter { !it.uploaded }.sortedBy { it.queuedAt }.take(limit)

    override suspend fun markFoundUploaded(sigs: List<String>) {
        val marked = outboundFound.map { if (it.signatureHex in sigs) it.copy(uploaded = true) else it }
        outboundFound.clear()
        outboundFound.addAll(marked)
    }

    override suspend fun countUploadableFound(): Int = outboundFound.count { !it.uploaded }
```

(If the fake DAO is a partial fake that only overrides some methods, mirror however it implements the `outbound_control` methods — the found methods are structurally identical. Add the import `net.guacamaya.mesh.OutboundFoundEntity`.)

3. Add a test:

```kotlin
    @Test
    fun drainsFoundQueueAndMarksUploaded() = runBlocking {
        // Arrange: one pending found frame, no SOS/control work.
        dao.outboundFound.add(
            OutboundFoundEntity(
                signatureHex = "ab".repeat(64),
                frame = ByteArray(118) { 1 },
                queuedAt = 1L,
            )
        )
        val summary = repo.uploadPending()
        assertEquals(IngestRepository.Outcome.SUCCESS, summary.outcome)
        assertEquals(1, summary.foundFramesSent)
        assertEquals(0, dao.countUploadableFound())
    }
```

Adapt the arrange/act names (`dao`, `repo`) to how the existing tests in that file construct them — reuse the same fixtures/setup the neighboring tests use.

- [ ] **Step 5: Run the tests**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests "net.guacamaya.ingest.IngestRepositoryTest"` — expected: PASS.
Then the full unit suite: `./gradlew :app:testDebugUnitTest` — expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/net/guacamaya/ingest/ android/app/src/test/kotlin/net/guacamaya/ingest/IngestRepositoryTest.kt
git commit -m "feat(android): drain Encontrado queue through /ingest foundFrames"
```

---

### Task 7: Android — "Marcar como encontrado" UI on the Radar screen

**Files:**
- Modify: `android/app/src/main/kotlin/net/guacamaya/ui/MapViewModel.kt`
- Modify: `android/app/src/main/kotlin/net/guacamaya/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `FoundPayload`/`buildFoundFrame`/`FoundFrame` (Task 4), `OutboundFoundEntity` + `insertOutboundFound` (Task 5), `IngestUploadWorker.enqueue` (existing), `ControlFrame.toHex` (existing), `Identity` (existing, `vm.identity: StateFlow<Identity?>` with `privateKeySeed`), `MessageEntity` (has `nodeId: ByteArray`, `msgId: Int`, `latE7: Int`, `lonE7: Int`).
- Produces: `MapViewModel.markFound(node: MessageEntity, finderLat: Double, finderLon: Double)`; RadarScreen shows a proximity pre-check dialog and queues the frame.

- [ ] **Step 1: Add `markFound` to `MapViewModel`**

In `android/app/src/main/kotlin/net/guacamaya/ui/MapViewModel.kt`, add these imports (merge with existing ones):

```kotlin
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import net.guacamaya.ingest.IngestUploadWorker
import net.guacamaya.mesh.MessageEntity
import net.guacamaya.mesh.OutboundFoundEntity
import net.guacamaya.proto.ControlFrame
import net.guacamaya.proto.FoundFrame
import net.guacamaya.proto.FoundPayload
import net.guacamaya.proto.buildFoundFrame
```

(Skip any import already present.) Then add this method next to `sendChat` (around line 164):

```kotlin
    /**
     * Queue a signed "Encontrado" report for [node]'s current SOS session and kick
     * the mule uploader. The backend re-verifies the signature and computes the
     * authoritative near/far verdict against the SOS's last reported point; the
     * finder's position travels inside the signed payload, never as loose JSON.
     */
    fun markFound(node: MessageEntity, finderLat: Double, finderLon: Double) {
        val id = identity.value ?: return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val frame = buildFoundFrame(
                FoundPayload(
                    latE7 = (finderLat * 1e7).toInt(),
                    lonE7 = (finderLon * 1e7).toInt(),
                    tsUnix = System.currentTimeMillis() / 1000,
                    targetNodeId = node.nodeId.copyOf(),
                    targetMsgId = node.msgId,
                    foundMsgId = SecureRandom().nextInt(0x10000),
                ),
                id.privateKeySeed,
            )
            val sigHex = ControlFrame.toHex(frame.copyOfRange(FoundFrame.SIG_OFF, FoundFrame.FRAME_LEN))
            dao.insertOutboundFound(
                OutboundFoundEntity(signatureHex = sigHex, frame = frame, queuedAt = System.currentTimeMillis())
            )
            IngestUploadWorker.enqueue(app)
        }
    }
```

(`Application` is already imported/used by `AndroidViewModel`; if not, add `import android.app.Application`.)

- [ ] **Step 2: Pass the callback into `RadarScreen`**

In `android/app/src/main/kotlin/net/guacamaya/ui/MainActivity.kt`, find the `RadarScreen(` call site inside `Screen()` (around line 633) and add the parameter:

```kotlin
            RadarScreen(
                latestNodes = latestNodes,
                neighborEstimates = neighborEstimates,
                onBack = { showRadar = false },
                onMarkFound = { node, lat, lon -> vm.markFound(node, lat, lon) },
            )
```

(Keep the existing named/positional argument style of the call site — the key change is the new `onMarkFound` lambda.) Then change the `RadarScreen` composable signature (around line 1269):

```kotlin
@Composable
private fun RadarScreen(
    latestNodes: List<MessageEntity>,
    neighborEstimates: List<NeighborEstimateEntity>,
    onBack: () -> Unit,
    onMarkFound: (MessageEntity, Double, Double) -> Unit,
) {
```

- [ ] **Step 3: Render the found section**

Inside `RadarScreen`, after the `signalTarget?.let { signal -> ... }` block and before `Spacer(Modifier.height(Space.md))` (the one preceding the "Precisión" card), add:

```kotlin
        targetNode?.let { node ->
            FoundReportSection(targetNode = node, location = location, onMarkFound = onMarkFound)
        }
```

Then add these composables + helpers near the bottom of the file, above `private fun ByteArray.toHex()`:

```kotlin
/** Local mirror of the backend's FOUND_PROXIMITY_METERS default (100 m). The
 *  backend computes the authoritative verdict; this only powers the pre-check UI. */
private const val FOUND_NEAR_METERS = 100.0

/**
 * "Marcar como encontrado": queues a signed Encontrado report for the radar's
 * current target. Before queueing, shows a proximity pre-check against the
 * target's last reported point — near (≤100 m) reads as "probable encontrado",
 * far warns "posible falso positivo" so trolls/mistakes are flagged, not hidden.
 */
@Composable
private fun FoundReportSection(
    targetNode: MessageEntity,
    location: Location?,
    onMarkFound: (MessageEntity, Double, Double) -> Unit,
) {
    // Without the target's fix there is no reference point to validate against.
    if (targetNode.latE7 == 0 && targetNode.lonE7 == 0) return
    val nodeKey = "${targetNode.nodeId.toHex()}-${targetNode.msgId}"
    var showDialog by remember(nodeKey) { mutableStateOf(false) }
    var queued by remember(nodeKey) { mutableStateOf(false) }

    Spacer(Modifier.height(Space.sm))
    if (queued) {
        Text(
            "Reporte \"Encontrado\" en cola — se subirá al backend con conexión",
            color = InfoC,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
        return
    }
    FilledTonalButton(
        onClick = { showDialog = true },
        enabled = location != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (location != null) "Marcar como encontrado" else "Marcar como encontrado (esperando GPS)")
    }

    if (showDialog && location != null) {
        val distance = haversineMeters(
            location.latitude, location.longitude,
            targetNode.latE7 / 1e7, targetNode.lonE7 / 1e7,
        )
        val near = distance <= FOUND_NEAR_METERS
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("¿Marcar nodo ${targetNode.nodeId.toHex().take(8)} como encontrado?") },
            text = {
                Text(
                    if (near) {
                        "✅ Estás a ~${distance.roundToInt()} m del último punto reportado. " +
                            "Probable encontrado."
                    } else {
                        "⚠️ Estás a ~${distance.roundToInt()} m del último punto reportado " +
                            "(más de ${FOUND_NEAR_METERS.roundToInt()} m). El reporte se marcará " +
                            "como posible falso positivo."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onMarkFound(targetNode, location.latitude, location.longitude)
                    queued = true
                    showDialog = false
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancelar") }
            },
        )
    }
}

/** Great-circle distance in meters — mirror of `haversineMeters` in @guacamaya/shared. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}
```

Add any missing imports to `MainActivity.kt` (merge with existing):

```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sqrt
```

(`sin`, `cos`, `roundToInt`, `Location`, `FilledTonalButton`, `TextAlign` are already imported — verify and only add what the compiler asks for.)

- [ ] **Step 4: Build + unit tests**

Run (from `android/`): `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Device verification note (required by repo rule)**

The Cursor rule `.cursor/rules/device-test-first.mdc` requires Android UI/BLE changes to be verified on a connected adb device before claiming they work. If a device is connected: `cd android && ./scripts/demo.sh install`, open the Radar with a second device broadcasting an SOS, tap "Marcar como encontrado", confirm the dialog shows the near/far pre-check, then check `./scripts/demo.sh logcat` for the `guacamaya.ingest` "found verdict" line once connectivity is available. If no device is connected, note in the task report that on-device verification is pending — do not claim the UI is verified.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/net/guacamaya/ui/MapViewModel.kt android/app/src/main/kotlin/net/guacamaya/ui/MainActivity.kt
git commit -m "feat(android): Marcar como encontrado action with proximity pre-check"
```

---

### Task 8: Docs — CHANGELOG.md + `backend_final.md` endpoint reference

**Files:**
- Create: `CHANGELOG.md` (repo root — it does not exist yet)
- Modify: `backend_final.md` (repo root — append the foundFrames contract)

**Interfaces:** none — documentation only.

- [ ] **Step 1: Create `CHANGELOG.md`**

Create `CHANGELOG.md` at the repo root:

```markdown
# Changelog

All notable changes to GuacaMalla are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- **"Encontrado" (Found) reports** — end users can mark an SOS victim as found by
  target ID + their own location, end to end:
  - New signed 118-byte *found frame* (`22 B payload + 32 B finder pubkey + 64 B
    Ed25519 signature`, domain `guacamaya.found-frame.v1`), byte-identical between
    `packages/shared/src/mesh/foundFrame.ts` and the Android
    `net.guacamaya.proto.FoundPayload`.
  - `POST /ingest` accepts a new `foundFrames` array. Every frame is re-verified
    zero-trust (CRC16 → GPS-fix sanity → domain-separated Ed25519) before anything
    is persisted; reports whose target SOS is unknown are rejected.
  - **Proximity validation against the SOS's last reference point**: the backend
    computes the haversine distance from the finder's signed GPS position to the
    target SOS's last reported point. Within `FOUND_PROXIMITY_METERS` (default
    100 m) → `near` / `likely_found`; further → `far` / `possible_false_positive`
    (⚠️ flags trolls and mistaken reports instead of hiding them). Verdicts are
    returned in the ingest response (`foundAccepted`, `foundDuplicate`,
    `foundRejected`, `foundReasons`, `foundResults[]`).
  - New community channel **`encontrado`** (`GET /channels/encontrado/records`,
    WebSocket broadcasts) holding the reports as `verified:false` records.
  - Android: "**Marcar como encontrado**" action on the Radar screen with a local
    near/far pre-check dialog, a new Room queue (`outbound_found`, DB v10), and
    data-mule upload of queued reports through the existing `/ingest` worker.
  - New backend env var `FOUND_PROXIMITY_METERS` (default 100).
```

- [ ] **Step 2: Document the endpoint contract in `backend_final.md`**

Append this section at the end of `backend_final.md` (repo root):

```markdown
## POST /ingest — `foundFrames` ("Encontrado" reports)

Alongside `frames` and `controlFrames`, the ingest body may carry
`"foundFrames": ["<base64>", ...]` — signed 118-byte found frames
(`22 B payload + 32 B finder pubkey + 64 B signature`; a 119-byte frame with a
leading hop-TTL byte is tolerated). No API key: zero-trust by signature.

Found payload v1 (22 bytes, big-endian; signature domain
`"guacamaya.found-frame.v1\n"` prefixed to the payload before signing):

| Offset | Field | Type |
|---|---|---|
| 0..3 | latE7 (finder latitude × 1e7) | int32 |
| 4..7 | lonE7 (finder longitude × 1e7) | int32 |
| 8..11 | tsUnix | u32 |
| 12..15 | targetNodeId = SHA-256(victim pubkey)[0..3] | 4 B |
| 16..17 | targetMsgId (SOS session) | u16 |
| 18..19 | foundMsgId (CSPRNG) | u16 |
| 20..21 | CRC16-CCITT over 0..19 | u16 |

Server-side, per frame: CRC → GPS-fix sanity (0,0 / out-of-range rejected) →
Ed25519 over the domain-prefixed payload → target lookup (latest
`solicito-ayuda` record with `payload.nodeId == targetNodeId` and
`payload.msgId == targetMsgId`; missing or fixless target ⇒ rejected,
`"unknown target sos"`). Accepted frames become `verified:false` records on
channel `encontrado` with `id = SHA-256(payload22)` (dedupe), plus a proximity
verdict computed from the finder's signed position vs the SOS's last reported
point: distance ≤ `FOUND_PROXIMITY_METERS` (default 100 m) ⇒
`proximity:"near"`, `verdict:"likely_found"`; otherwise `proximity:"far"`,
`verdict:"possible_false_positive"`.

Response additions:

```json
{
  "foundAccepted": 1,
  "foundDuplicate": 0,
  "foundRejected": 0,
  "foundReasons": {},
  "foundResults": [
    {
      "id": "<sha256 hex>",
      "targetRecordId": "<sha256 hex of the SOS record>",
      "distanceMeters": 33,
      "proximity": "near",
      "verdict": "likely_found"
    }
  ]
}
```

Verdicts are returned for duplicates too (the uploading mule is usually the
finder's own phone). Recommended app flow: queue the frame in `outbound_found`,
drain it with the regular mule upload, and log/render the returned verdict.
```

- [ ] **Step 3: Final full verification**

Run: `bun test` — expected: green.
Run: `bun run --cwd backend tsc --noEmit` — expected: no errors.
Run (from `android/`): `./gradlew :app:testDebugUnitTest` — expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md backend_final.md
git commit -m "docs: changelog + ingest foundFrames contract for Encontrado"
```

---

## Self-review checklist (already applied)

- Spec coverage: mark-by-ID+location (Tasks 1, 4, 7), proximity validation near/far with 100 m default (Tasks 1–3, 7), `/ingest` as the single entry point (Task 3), mobile availability (Tasks 4–7), CHANGELOG (Task 8), haiku/sonnet subagent guidance (header).
- Type consistency: `FoundPayloadFields`/`FoundPayload` field names and offsets match across TS and Kotlin; `FoundVerdict` fields match the backend `FoundResultEntry`; DAO method names (`insertOutboundFound`/`selectUploadableFound`/`markFoundUploaded`/`countUploadableFound`) are identical in Tasks 5, 6, and 7.
- Rejected alternatives (do not re-open): reusing the control-frame layout (no room for lat/lon), a trusted JSON endpoint (violates the zero-trust/no-client-location invariant), emitting ledger `SOS_RESOLVED` events (the witness-quorum resolve flow is the authoritative disarm; Encontrado is an informational community report).
