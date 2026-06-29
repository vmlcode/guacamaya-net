# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this branch is

The **`develop` branch is the consolidated GuacaMalla monorepo** holding both halves of the product:

- **`backend/` + `packages/`** — the GuacaMalla Red backend (Bun + TypeScript). It is the **data-mule
  ingestion point** for the mesh (`POST /ingest`), serves official signed channels, and feeds the
  moving-map location history. The mesh works without it; the backend is never a hard dependency.
- **`android/`** — the native Kotlin + Compose **GuacaMalla mesh app**, the connectionless
  BLE/Wi-Fi-Aware mesh. A **self-contained Gradle project** (its own `gradlew`, `build.gradle.kts`,
  `docs/`, `CLAUDE.md`); open `android/` directly in Android Studio. See **`android/CLAUDE.md`** for
  the mesh side.

The abandoned Expo app that used to live in `app/` has been removed. `android/` is **not** a Bun
workspace — keep it out of the root `package.json` workspaces.

Working language of comments/docs is Spanish in places; code identifiers are English.

## Brand rules

- The product is **GuacaMalla Net** (mesh app + optional backend). Use *GuacaMalla* in prose, docs,
  comments, and UI. **SOSNet** is a retired name — do not use it.
- **Code identifiers intentionally stay `guacamaya`** — the brand fix (Guacamaya→GuacaMalla) was
  *visible + docs only*. Do **not** "fix" these to `guacamalla`; doing so changes the app identity,
  breaks deployments, regenerates device keys, or desyncs the wire/protocol:
  - Android package + `namespace`/`applicationId` (`net.guacamaya`) and action strings (`net.guacamaya.action.*`).
  - npm/workspace packages (`@guacamaya/*`, `guacamaya-net`) and env vars (`GUACAMAYA_ADMIN_KEY`, `GUACAMAYA_*_KEY`).
  - Runtime keys: Keystore alias `guacamaya_master`, Room DB `guacamaya.db`, WorkManager `guacamaya-ingest-upload`,
    notification channel/log tags (`guacamaya.*`), and the WS subprotocol prefix `guacamaya.` (matched on both sides).
- Wire-format constants live in `packages/shared/src/mesh/` and must stay byte-identical with
  `net.guacamaya.proto.*` on Android.
- Exception: the git branch name **`init-sosnet`** is a literal remote branch name until renamed.

> **Context rule:** when a request touches anything under `backend/` or the shared user flow (API
> contracts, data shapes shared between mesh and backend, WebSocket events, channel/location
> ingestion), read `backend/CLAUDE.md` first — it has the three-layer stack pattern, the dedup
> invariant, environment setup, and per-domain commands.

> **Mobile integration:** `backend_final.md` (repo root) is the authoritative per-endpoint reference
> for wiring the Android app to the backend — auth model (which keys the app must/must not hold), the
> 118-byte upload-frame layout for the `IngestClient`, every endpoint's request/response, the resolve
> witness-signing format, and the recommended mule-upload flow. Read it before touching the
> mesh↔backend HTTP/WS contract or building the Android `IngestClient`.

## Run / test (Bun-first — never npm/node)

```bash
bun install
bun run dev:backend     # bun --watch backend/src/index.ts  (HTTP + WS on :3000)
bun test                # shared-package unit tests (bun:test)
bun run build           # typecheck/build shared + backend
```

Bun installs to `~/.bun/bin`. Runs out of the box with **no database**: if `SUPABASE_URL` /
`SUPABASE_SERVICE_ROLE_KEY` are unset, the data layer falls back to in-memory stores
(`backend/src/channels/store.ts`, `backend/src/locations/store.ts`). Configure `backend/.env` from
`backend/.env.example`; set `BACKEND_PRIVATE_KEY_HEX` so the server's Ed25519 identity (and public
key) stays stable across reboots — otherwise a fresh ephemeral keypair is generated each boot (it
warns on startup).

## Architecture

The unit of data is a **`ChannelRecord`** (`packages/shared/src/types.ts`): `{ id, channel,
timestamp, ttl, author, verified, payload, sig? }`. `id` is a deterministic SHA-256 of canonical
content (`packages/shared/src/crypto.ts` → `getRecordId`), so storage dedupes by `id` — the same
union+dedupe the mesh gossip log uses. `verified:true` means **backend-signed official**; community
reports are always `verified:false`.

A second unit, **`LocationPoint`** (`{ id, deviceId, lat, lon, timestamp, accuracy? }`), carries GPS
trajectory for the moving-map dashboard. Like records, `id` = SHA-256 of canonical content
(`getLocationId`) so re-uploaded points dedupe.

### Layout

- `packages/shared/` — `ChannelRecord` + `LocationPoint` types, `getRecordId`, `getLocationId`,
  `verifyRecordSignature`, `mergeLogs` (union + dedupe + signature check). Pure, `bun:test` coverage.
  The contract both sides share. Keep it framework-agnostic.
- `backend/src/index.ts` — Fastify bootstrap (CORS, rate-limit 100/min), registers route plugins, WS, graceful shutdown.
- `backend/src/channels/routes.ts` — channel HTTP API + `/ingest` (see below). `store.ts` — in-memory fallback.
- `backend/src/locations/routes.ts` — location HTTP API (see below). `store.ts` — in-memory fallback.
- `backend/src/crypto/` — `keys.ts` (server identity), `signer.ts` (signs official records).
- `backend/src/mesh/frame.ts` — **decodes + zero-trust-verifies GuacaMalla binary mesh frames** for `/ingest`.
- `backend/src/db/` — `channelsRepo.ts`, `locationsRepo.ts` (Supabase with in-memory fallback), `supabase.ts`. Schema: `backend/supabase/schema.sql`.
- `backend/src/ws/server.ts` — WebSocket `/ws`, subscribe/unsubscribe per channel, `broadcastRecord`, `broadcastLocation`.

### HTTP API

- `GET /pubkey` — backend public key (clients verify official records against it).
- `GET /channels` — channel list.
- `GET /channels/:id/records?since=<ms>` — records since a timestamp.
- `POST /channels/:id/records` — create an **official** record: backend signs it (`verified:true`), persists, broadcasts over WS.
- `POST /ingest` — **data-mule upload of signed binary mesh frames** (see below). Also populates the
  location history: each verified frame's lat/lon becomes a `LocationPoint`.
- `GET /locations?since=<ms>&deviceId=<id>` — location points for the moving-map dashboard (read-only).

### `POST /ingest` — the data-mule bridge (most important flow)

Body: `{ "frames": ["<base64>", ...] }`. Each frame is the GuacaMalla BLE service-data **with the leading
hop-TTL byte stripped** → 118 bytes = `22 B payload + 32 B pubkey + 64 B signature` (a full 119-byte
frame is tolerated by dropping the first byte).

**Zero-trust: every frame is re-verified before anything is persisted** — the backend never trusts the
client. `decodeAndVerifyFrame` (`backend/src/mesh/frame.ts`) mirrors the Kotlin `FloodRouter` reject
cascade, cheapest first: CRC16-CCITT (byte-for-byte mirror of `Crc16.ccitt`) → pubkey-binding
(`SHA-256(pubkey)[0..4] == node_id`) → Ed25519 verify over the 22-byte payload (mirrors Kotlin
`Signer.verify`; uses `@noble/ed25519`). The replay/timestamp-skew check is intentionally **omitted**
here — a mule legitimately uploads stale reports. Verified frames become `verified:false` community
records on channel `solicito-ayuda`, with `id = SHA-256(payload)` for dedupe. Response reports
`{ ingested, duplicate, rejected, reasons }`.

Note that the 22-byte payload **already carries lat/lon** (bytes 0..7, int32 E7) and a unix-seconds
timestamp — so a verified SOS frame is itself a geolocated point. `decodeAndVerifyFrame` returns that
`LocationPoint` alongside the record (`deviceId = device-<origin pubkey>`, not the mule; no accuracy
field; `null` when the frame has no GPS fix), and `/ingest` persists it via `locationsRepo` and
broadcasts it to `"locations"` WS subscribers — same zero-trust gate, no separate trusted endpoint.

## Conventions worth keeping

- **Bun only** for package management and scripts (project preference). `.env.example` mentioning `npm` is stale.
- `/ingest` must **never** persist a frame whose signature it hasn't re-verified. Verification is the gate.
- Keep `packages/shared` framework-agnostic — it's the contract; don't pull backend-only deps into it.
- The binary frame layout in `frame.ts` mirrors `proto/Payload.kt` on the mesh branch; if the wire
  format changes there (offsets, sizes, CRC), update both or `/ingest` silently rejects valid frames.
- **Always recompute `id` server-side** (`getRecordId` / `getLocationId`) on ingest — never trust a
  client-supplied id.
- **Locations are frame-derived, never client-trusted.** The canonical, authenticated source of a
  device's position is the **lat/lon inside a verified mesh frame** — `decodeAndVerifyFrame` emits the
  `LocationPoint` and `/ingest` persists it. There is intentionally **no** trusted-JSON location
  ingest endpoint (the original `POST /ingest/locations` was removed): the same Ed25519 gate that
  guards records guards positions. `deviceId` is derived from the verified pubkey, never supplied by
  the client. `GET /locations` stays read-only.
