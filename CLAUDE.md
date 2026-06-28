# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this branch is

The **`develop` branch is the Guacamaya Red backend** — a Bun + TypeScript monorepo. Its job in the
current design is narrow: be the optional **data-mule ingestion point** for the SOSNet Android mesh.
Phones that recover connectivity upload the signed mesh frames they collected; the backend
re-verifies and persists them.

> The Expo app under `app/` is **dropped** — the native Kotlin app (SOSNet, on the `init-sosnet`
> branch) replaces it. Ignore `app/`; do not build features there. The two products live on separate
> branch lineages in the same repo — see the SOSNet branch's `CLAUDE.md` for the mesh side.

Working language of comments/docs is Spanish in places; code identifiers are English.

> **Context rule:** when a request touches anything under `backend/` or the shared user flow (API
> contracts, data shapes shared between mesh and backend, WebSocket events, channel/location
> ingestion), read `backend/CLAUDE.md` first — it has the three-layer stack pattern, the dedup
> invariant, environment setup, and per-domain commands.

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
- `backend/src/mesh/frame.ts` — **decodes + zero-trust-verifies SOSNet binary mesh frames** for `/ingest`.
- `backend/src/db/` — `channelsRepo.ts`, `locationsRepo.ts` (Supabase with in-memory fallback), `supabase.ts`. Schema: `backend/supabase/schema.sql`.
- `backend/src/ws/server.ts` — WebSocket `/ws`, subscribe/unsubscribe per channel, `broadcastRecord`, `broadcastLocation`.

### HTTP API

- `GET /pubkey` — backend public key (clients verify official records against it).
- `GET /channels` — channel list.
- `GET /channels/:id/records?since=<ms>` — records since a timestamp.
- `POST /channels/:id/records` — create an **official** record: backend signs it (`verified:true`), persists, broadcasts over WS.
- `POST /ingest` — **data-mule upload of signed binary mesh frames** (see below).
- `GET /locations?since=<ms>&deviceId=<id>` — location points for the moving-map dashboard.
- `POST /ingest/locations` — batch upload of GPS points. ⚠️ See the location convention below — this
  endpoint currently trusts client JSON and does **not** fit the zero-trust ingestion model yet.

### `POST /ingest` — the data-mule bridge (most important flow)

Body: `{ "frames": ["<base64>", ...] }`. Each frame is the SOSNet BLE service-data **with the leading
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
timestamp — so a verified SOS frame is itself a geolocated point. See the location convention below.

## Conventions worth keeping

- **Bun only** for package management and scripts (project preference). `.env.example` mentioning `npm` is stale.
- `/ingest` must **never** persist a frame whose signature it hasn't re-verified. Verification is the gate.
- Keep `packages/shared` framework-agnostic — it's the contract; don't pull backend-only deps into it.
- The binary frame layout in `frame.ts` mirrors `proto/Payload.kt` on the SOSNet branch; if the wire
  format changes there (offsets, sizes, CRC), update both or `/ingest` silently rejects valid frames.
- **Always recompute `id` server-side** (`getRecordId` / `getLocationId`) on ingest — never trust a
  client-supplied id.
- **Location convention (in flux):** locations were added on `BR-01-Add-location-on-backend` as a
  standalone trusted-JSON endpoint (`POST /ingest/locations`). Under develop's zero-trust data-mule
  model this is a tension: the canonical, authenticated source of a device's position is the **lat/lon
  inside a verified mesh frame**, not unsigned JSON. The intended direction is to derive
  `LocationPoint`s from `decodeAndVerifyFrame` (deviceId from the verified pubkey, not client-supplied)
  and treat the trusted JSON endpoint as dev-only / non-mesh. Don't extend the trusted path.
