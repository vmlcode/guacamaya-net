# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**SOSNet** — a connectionless L2 mesh for broadcasting SOS distress signals when cell
infrastructure is down (earthquake/disaster scenario). Pure native Android, Kotlin + Jetpack
Compose. Phones act as **radio beacons, not peers**: no pairing, no handshake, no credential
exchange. Authenticity is moved off the link layer (which is intentionally open — a public SOS
should be receivable and relayable by anyone in range) onto the **application layer via Ed25519
signatures**.

The Android app is **serverless by design** — the mesh works with zero infrastructure. A backend
exists *only* as an optional "data-mule" sink (see below), never as a dependency: the app must run
fully without it.

The `docs/` directory is the authoritative protocol spec and is treated as a hackathon deliverable —
keep it in sync when changing wire format, crypto, or flows. Working language of README/docs is Spanish.

## Repository structure — consolidated monorepo (`develop`)

This note covers the **SOSNet Android app**, which lives in the **`android/`** folder of the
consolidated `develop` monorepo. It is a self-contained Gradle project — run all `./gradlew` commands
below from `android/`, and open `android/` (not the repo root) in Android Studio.

| Path (on `develop`) | Product | Stack | Role |
|---|---|---|---|
| `android/` | **SOSNet** (this project) | Native Android, Kotlin + Compose | The connectionless BLE/Wi-Fi-Aware mesh described below. |
| `backend/`, `packages/` | **Guacamaya Red backend** | Bun + TypeScript (Fastify, `@noble/ed25519`, Supabase) | Optional **data-mule ingestion point** for mesh reports. See the repo-root `CLAUDE.md`. |

The two crypto schemes are **not** interchangeable: SOSNet signs the raw 22-byte binary payload;
the backend's *official-channel* records sign a JSON canonical hash. The only bridge is `/ingest`.

### Data-mule bridge (backend `POST /ingest`)

A phone that recovers connectivity can upload the mesh frames it collected. Contract:

```
POST /ingest   { "frames": ["<base64 of the 118-byte frame>", ...] }
```

The 118-byte upload frame is the BLE frame **with the leading hop-TTL byte stripped**
(`22 B payload + 32 B pubkey + 64 B signature`; a full 119-byte frame is tolerated). **Zero-trust:**
the backend re-runs the SOSNet reject cascade (CRC16 → pubkey-binding → Ed25519 verify over the
22-byte payload, mirroring `crypto.Signer.verify`) on every frame before persisting. Verified frames
are stored as `verified:false` community reports on channel `solicito-ayuda`. Backend logic lives in
`backend/src/mesh/frame.ts` and `backend/src/channels/routes.ts`. Run it with `bun run dev:backend`
(set `BACKEND_PRIVATE_KEY_HEX` in `backend/.env` for a stable identity; works with an in-memory store
if Supabase is unconfigured). **No Kotlin uploader exists yet** — wiring an `IngestClient` is open work.

## Build / test / run

Requires **JDK 17+** (AGP 8.5.2 runs fine on JDK 21) and Android SDK with `platform-android-34` +
`build-tools;34.0.0`. Point Gradle at the SDK via `local.properties` (`sdk.dir=...`, gitignored) or
`ANDROID_HOME`.

```bash
./gradlew :app:assembleDebug                  # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest              # all JVM unit tests
./gradlew :app:testDebugUnitTest --tests "org.sosnet.proto.PayloadTest"          # one class
./gradlew :app:testDebugUnitTest --tests "org.sosnet.crypto.SignerTest.*verify*" # one method pattern
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Demo helper (build + install + debugging in one place):

```bash
./scripts/demo.sh install   # assembleDebug + adb install on the connected device
./scripts/demo.sh logcat    # colorized logcat stream (scripts/logcat_pretty.py)
./scripts/demo.sh tamper    # generate a bit-flipped frame to demo signature rejection
python3 scripts/tamper_test.py   # host-side proof: verify=True valid frame, verify=False tampered
```

`demo.sh` auto-resolves a JDK (caller `JAVA_HOME` → Android Studio's bundled JBR at
`/opt/android-studio/jbr` → `java` on `PATH`); override `JAVA_HOME` to force one. Use `ANDROID_SERIAL`
to select among multiple attached devices. Unit tests run on the JVM
(`unitTests.isReturnDefaultValues = true`), so they cover pure logic — codec, CRC, crypto — not
Android framework calls.

## Platform constraints

- **minSdk 26** — driven by Wi-Fi Aware (Android 8.0+). targetSdk/compileSdk 34.
- **Ed25519 via BouncyCastle 1.78.1**, not `java.security` — the JDK provider only has Ed25519 from
  API 33, but minSdk is 26. Always sign/verify through `crypto.Signer`.
- BLE broadcasting requires a chip supporting **BLE 5 Extended Advertising** (`isLeExtendedAdvertisingSupported`);
  `Broadcaster.create` returns null otherwise. Device must be API 26+ with Wi-Fi Aware for the full demo.

## Architecture — the core idea

Every datum is a **fixed 22-byte signed payload** that rides inside a single broadcast frame. There
are **no connections** — nodes radiate structured bursts into the air and listen for whatever floats
by. Same frame regardless of transport; the route doesn't matter.

### Two transport planes (`ble/` and `aware/`)

| Plane | Radio | Carries | Frame |
|---|---|---|---|
| Control / discovery | BLE 5 Extended Advertising (`Broadcaster` / `Observer`) | the 119 B frame as BLE service data | non-connectable, non-scannable, PHY 1M primary + Coded secondary |
| Data (light, ≤255 B) | Wi-Fi Aware NAN service-discovery (`NanMessenger`) | same 118 B as SSI | publish/subscribe, no IP, no auth |
| Data (heavy, >255 B) | Wi-Fi Aware NAN Data Path (`NanDataPath`) | larger payloads, ephemeral | auto-negotiated |

### The 119-byte frame

`1 B unsigned hop TTL + 22 B payload + 32 B Ed25519 public key + 64 B signature`
(`ble/BleConfig.SERVICE_DATA_SIZE`; offsets TTL@0, payload@1, pubkey@23, sig@55).
The payload (`proto/Payload.kt`, big-endian) is `latE7, lonE7, tsUnix(uint32), nodeId(4 B), flags,
sosType, msgId(uint16)`, ending in a CRC16-CCITT over the first 20 bytes. `nodeId` = first 4 bytes
of `SHA-256(pubkey)` — this **binds the payload's identity to the key that signed it**, so a pubkey
swap is detectable. `flags` packs `hasHeavy / critical / batteryBucket(0-3) / hopTtl(0-15)`; the
signed `hopTtl` is the origin's *initial* value only. The **live hop budget is the unsigned leading
byte**, decremented per relay so the signed payload stays immutable (re-signing is impossible —
node_id is bound to the origin key). The 64-byte signature covers the full 22-byte payload and is
concatenated by the BLE layer, not stored in `Payload` itself.

### Identity (`crypto/Identity.kt`)

One Ed25519 keypair per install, generated lazily. The 32-byte private seed is AES-GCM encrypted
under an Android Keystore master key; ciphertext lives in app-private SharedPreferences. The public
key is the durable node identity.

### The reject cascade (`mesh/FloodRouter.kt`) — most important control flow

Inbound frames pass an ordered, **cheapest-check-first** gauntlet before being stored or relayed:

1. `SHA-256(pubkey)[0..4] == payload.nodeId` (pubkey-binding; defeats key swap)
2. `Payload.decode` succeeds (CRC16 — cheap reject before the expensive verify)
3. `|tsUnix − now| ≤ 300 s` (replay window)
4. Ed25519 `Signer.verify` (the expensive step, last)

On pass → `DedupeCache.admit` → persist to Room (then `pruneOldKeeping(MAX_STORED_MESSAGES=500)`) →
if fresh, **relay the payload/pubkey/sig bytes unchanged** with the unsigned hop TTL decremented;
stop relaying once it would hit 0 (the frame is still stored so the local user sees it). Loop-back
within the window is also suppressed by the dedupe cache, keyed by `(nodeId, msgId)` with LRU +
5-min TTL.

### Persistence & UI

`mesh/MessageStore.kt` — Room DB (`SOSNetDatabase`, table `messages`); only verified frames are
written. `MessageDao.observeRecent` exposes a `Flow` the UI collects. `service/SosForegroundService.kt`
is a `connectedDevice`-type foreground service that keeps the radios alive when backgrounded; it owns
the Broadcaster/Observer/FloodRouter and responds to `ACTION_START/STOP/OBSERVE_ON/OBSERVE_OFF`
intents. `ui/MainActivity.kt` + `MapViewModel` — single Compose screen, two toggles
(Broadcast / Observe) that fire those intents, plus an OSMDroid (offline-capable) map of received SOS pins.

## Module map (`app/src/main/kotlin/org/sosnet/`)

- `proto/` — `Payload` codec, `Flags`, `SosType`, `Crc16`. Pure, well-tested, no Android deps.
- `crypto/` — `Identity` (keystore-sealed keypair), `Signer` (BouncyCastle Ed25519).
- `ble/` — `Broadcaster`, `Observer`, `BleConfig` (service UUID, advertising params).
- `aware/` — `NanMessenger`, `NanDataPath`, `AwareConfig` (Wi-Fi Aware).
- `mesh/` — `FloodRouter`, `DedupeCache`, `MessageStore` (Room).
- `service/` — `SosForegroundService`.
- `ui/` — Compose UI, map, theme.

## Conventions worth keeping

- Sizes are asserted via `require()` at boundaries (22/32/64; 119 total BLE frame) — keep these
  invariants; they're the contract between layers.
- BLE Observer filters the service UUID **in software**, not via hardware `ScanFilter` — many stacks
  drop extended service-data when filtering in silicon. Don't "optimize" this back to a hardware filter.
- Verify is always the last gate. Never persist or rebroadcast before the full cascade passes.
- The BLE service UUID in `BleConfig` is a placeholder — change before any production use.
