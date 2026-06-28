# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this branch is

The **`develop` branch is the consolidated monorepo** holding both products:

- **`backend/` + `packages/`** — the Guacamaya Red backend (Bun + TypeScript). Its job is narrow: be
  the optional **data-mule ingestion point** for the SOSNet Android mesh. Phones that recover
  connectivity upload the signed mesh frames they collected; the backend re-verifies and persists them.
- **`android/`** — the SOSNet native Android app (Kotlin + Compose), the connectionless BLE/Wi-Fi-Aware
  mesh. It is a **self-contained Gradle project** (its own `gradlew`, `build.gradle.kts`, `docs/`,
  `CLAUDE.md`). Open `android/` directly in Android Studio. See **`android/CLAUDE.md`** for the mesh side.

The dropped Expo app that used to live in `app/` has been removed — the Kotlin app replaces it.
`android/` is **not** a Bun workspace; keep it out of the root `package.json` workspaces.

Working language of comments/docs is Spanish in places; code identifiers are English.

## Run / test (Bun-first — never npm/node)

```bash
bun install
bun run dev:backend     # bun --watch backend/src/index.ts  (HTTP + WS on :3000)
bun test                # shared-package unit tests (bun:test)
bun run build           # typecheck/build shared + backend
```

Bun installs to `~/.bun/bin`. Runs out of the box with **no database**: if `SUPABASE_URL` /
`SUPABASE_SERVICE_ROLE_KEY` are unset, the data layer falls back to an in-memory store
(`backend/src/channels/store.ts`). Configure `backend/.env` from `backend/.env.example`; set
`BACKEND_PRIVATE_KEY_HEX` so the server's Ed25519 identity (and public key) stays stable across
reboots — otherwise a fresh ephemeral keypair is generated each boot (it warns on startup).

## Architecture

The unit of data is a **`ChannelRecord`** (`packages/shared/src/types.ts`): `{ id, channel,
timestamp, ttl, author, verified, payload, sig? }`. `id` is a deterministic SHA-256 of canonical
content (`packages/shared/src/crypto.ts` → `getRecordId`), so storage dedupes by `id` — the same
union+dedupe the mesh gossip log uses. `verified:true` means **backend-signed official**; community
reports are always `verified:false`.

### Layout

- `packages/shared/` — `ChannelRecord` type, `getRecordId`, `verifyRecordSignature`, `mergeLogs`
  (union + dedupe + signature check). Pure, has `bun:test` coverage. The contract both sides share.
- `backend/src/index.ts` — Fastify bootstrap (CORS, rate-limit 100/min), routes, WS, graceful shutdown.
- `backend/src/channels/routes.ts` — HTTP API (see below).
- `backend/src/crypto/` — `keys.ts` (server identity), `signer.ts` (signs official records).
- `backend/src/mesh/frame.ts` — **decodes + zero-trust-verifies SOSNet binary mesh frames** for `/ingest`.
- `backend/src/db/` — `channelsRepo.ts` (Supabase with in-memory fallback), `supabase.ts`. Schema: `backend/supabase/schema.sql`.
- `backend/src/ws/server.ts` — WebSocket `/ws`, subscribe/unsubscribe per channel, `broadcastRecord`.

### HTTP API

- `GET /pubkey` — backend public key (clients verify official records against it).
- `GET /channels` — channel list.
- `GET /channels/:id/records?since=<ms>` — records since a timestamp.
- `POST /channels/:id/records` — create an **official** record: backend signs it (`verified:true`), persists, broadcasts over WS.
- `POST /ingest` — **data-mule upload from the mesh** (see below).

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

## Conventions worth keeping

- **Bun only** for package management and scripts (project preference). `.env.example` mentioning `npm` is stale.
- `/ingest` must **never** persist a frame whose signature it hasn't re-verified. Verification is the gate.
- Keep `packages/shared` framework-agnostic — it's the contract; don't pull backend-only deps into it.
- The binary frame layout in `frame.ts` mirrors `proto/Payload.kt` on the SOSNet branch; if the wire
  format changes there (offsets, sizes, CRC), update both or `/ingest` silently rejects valid frames.
