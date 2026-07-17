# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this branch is

The **`develop` branch is the consolidated GuacaMalla monorepo** holding every half of the product:

- **`backend/` + `packages/`** — the GuacaMalla Red backend (Bun + TypeScript + Fastify). It is the
  **data-mule ingestion point** for the mesh (`POST /ingest`), serves official signed channels, runs
  the resolve/ledger flow, and feeds the moving-map dashboard. The mesh works without it; the backend
  is never a hard dependency.
- **`android/`** — the native Kotlin + Compose **GuacaMalla mesh app**, the connectionless
  BLE/Wi-Fi-Aware mesh. A **self-contained Gradle project** (its own `gradlew`, `build.gradle.kts`,
  `docs/`, `CLAUDE.md`); open `android/` directly in Android Studio. See **`android/CLAUDE.md`** for
  the mesh side. A Cursor rule (`.cursor/rules/device-test-first.mdc`) requires Android BLE/UI changes
  to be verified on connected adb devices (`cd android && ./scripts/demo.sh install` /
  `device-test` / `logcat`) before claiming they work.
- **`guacamalla-landing/landing/`** — the public marketing site (Vite + React 19 + Tailwind 4 +
  shadcn/Radix). It has its **own `bun.lock`** and is **not** a root Bun workspace; run it via
  `bun run dev:landing`. It is **connected to Lovable** (see its `AGENTS.md`): never force-push,
  rebase, or amend commits already pushed on its branch, and keep the branch in a working state.

`android/` is **not** a Bun workspace — keep it (and the landing) out of the root `package.json`
workspaces (`packages/*`, `backend`). The `app/` directory is a leftover stub (a lone
`expo-env.d.ts`) from the removed Expo app — nothing lives there.

Working language of comments/docs is Spanish in places; code identifiers are English.

## Brand rules

- The product is **GuacaMalla** (mesh app + optional backend). Use *GuacaMalla* in prose, docs,
  comments, and UI. **SOSNet** is a retired name — do not use it.
- **Code identifiers intentionally stay `guacamaya`** — the brand fix (Guacamaya→GuacaMalla) was
  *visible + docs only*. Do **not** "fix" these to `guacamalla`; doing so changes the app identity,
  breaks deployments, regenerates device keys, or desyncs the wire/protocol:
  - Android package + `namespace`/`applicationId` (`net.guacamaya`) and action strings (`net.guacamaya.action.*`).
  - npm/workspace packages (`@guacamaya/*`, `guacamaya-net`) and env vars (`GUACAMAYA_ADMIN_KEY`, `GUACAMAYA_*_KEY`).
  - Runtime keys: Keystore alias `guacamaya_master`, Room DB `guacamaya.db`, WorkManager `guacamaya-ingest-upload`,
    notification channel/log tags (`guacamaya.*`), signing domains (`guacamaya.rescuer-cert.v1`,
    `CONTROL_SIGN_DOMAIN`, …), and the WS subprotocol prefix (matched on both sides).
- Wire-format constants live in `packages/shared/src/mesh/` and must stay byte-identical with
  `net.guacamaya.proto.*` on Android.
- Exception: the git branch name **`init-sosnet`** is a literal remote branch name until renamed.

> **Context rule:** when a request touches anything under `backend/` or the shared user flow (API
> contracts, data shapes shared between mesh and backend, WebSocket events, channel/location
> ingestion, auth keys), read `backend/CLAUDE.md` first — it has the three-layer stack pattern, the
> dedup invariant, the security/key model, environment setup, and per-domain commands.

> **Mobile integration:** `backend_final.md` (repo root) is the authoritative per-endpoint reference
> for wiring the Android app to the backend — auth model (which keys the app must/must not hold), the
> 118-byte upload-frame layout for the `IngestClient`, every endpoint's request/response, the resolve
> witness-signing format, and the recommended mule-upload flow. Read it before touching the
> mesh↔backend HTTP/WS contract or building against the Android `IngestClient`.

## Run / test (Bun-first — never npm/node)

```bash
bun install
bun run dev:backend     # bun --watch backend/src/index.ts  (HTTP + WS on :3000)
bun run dev:landing     # Vite dev server for the landing site
bun test                # all unit tests (packages/shared + backend, bun:test)
bun test packages/shared/src/ledger.test.ts   # single test file
bun run build           # typecheck/build shared + backend + landing
bun run keygen          # generate BACKEND_PRIVATE_KEY_HEX + GUACAMAYA_ADMIN_KEY
```

Bun installs to `~/.bun/bin`. Runs out of the box with **no database**: if `SUPABASE_URL` /
`SUPABASE_SERVICE_ROLE_KEY` are unset, the data layer falls back to in-memory stores (`store.ts` in
each domain). Configure `backend/.env` from `backend/.env.example`; set `BACKEND_PRIVATE_KEY_HEX` so
the server's Ed25519 identity stays stable across reboots (otherwise an ephemeral keypair is
generated each boot, with a startup warning). `NODE_ENV=production` refuses to start without
`GUACAMAYA_ADMIN_KEY`.

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

- `packages/shared/` — the contract both sides share. Pure, framework-agnostic, `bun:test` coverage:
  - `types.ts`, `crypto.ts`, `merge.ts` — `ChannelRecord`/`LocationPoint`, `getRecordId`/`getLocationId`,
    `verifyRecordSignature`, `mergeLogs` (union + dedupe + signature check).
  - `canonical.ts` — canonical JSON serialization + `SosId`/`EventId` formats; every signed structure
    hashes through it.
  - `control.ts` — `ControlReceipt` verification, **rescuer certificates** (7-day max, domain
    `guacamaya.rescuer-cert.v1`) and the signed **CRL** the backend issues.
  - `ledger.ts` — the SOS lifecycle event chain: `SOS_CREATED | SOS_RESOLVED | SOS_CANCELLED |
    SOS_EXPIRED | KEY_REVOKED`, event hashing/chaining, replay guard.
  - `resolve.ts` — witness envelope + quorum verification for finder co-signed disarms.
  - `mesh/` — binary wire format: `constants.ts` (offsets/sizes/domains), `crc16.ts` (CRC16-CCITT),
    `controlFrame.ts` (22-byte control payload, byte-identical with Android `ControlPayload.kt`,
    domain-separated signature so SOS and control signatures are never interchangeable).
- `backend/src/index.ts` — Fastify bootstrap (helmet+CSP, CORS, rate limits), registers all route
  plugins, WS, periodic sweep, graceful shutdown.
- `backend/src/<domain>/` — one directory per domain, each following the same three-layer stack
  (`routes.ts` → `*Repo.ts` Supabase-or-fallback → `store.ts` in-memory): `channels/` (+ `/ingest`),
  `locations/`, `resolve/` (witness quorum + evidence upload), `control/` (NAN control-receipt
  ingress + replay guard), `ledger/` (SOS lifecycle chain), `waitlist/` (landing signups → Resend
  email), `dashboard/` (self-contained Leaflet map page, public, coordinates degraded ~1 km).
- `backend/src/crypto/` — `keys.ts` (server identity), `signer.ts` (signs official records).
- `backend/src/mesh/frame.ts` — **decodes + zero-trust-verifies GuacaMalla binary mesh frames** for
  `/ingest`; `controlFrame.ts` for control frames.
- `backend/src/security/` — API-key auth (timing-safe), env-driven config, per-route rate limits,
  input validation, `keygen.ts`. Key model (admin/read/WS keys) is detailed in `backend/CLAUDE.md`.
- `backend/src/db/` — Supabase repos with in-memory fallback. Schema: `backend/supabase/schema.sql`.
- `backend/src/ws/server.ts` — WebSocket `/ws` (key-gated), subscribe/unsubscribe per channel,
  `broadcastRecord`, `broadcastLocation`, `broadcastResolve`.

### HTTP API (details + auth per endpoint in `backend_final.md`)

- `GET /pubkey` — backend public key (clients verify official records/receipts against it).
- `GET /channels`, `GET /channels/:id/records?since=<ms>` — channel reads.
- `POST /channels/:id/records` — create an **official** record (admin key; backend signs it
  `verified:true`, persists, broadcasts over WS).
- `POST /ingest` — **data-mule upload of signed binary mesh frames** (see below).
- `GET /locations?since=<ms>&deviceId=<id>` — location points (read key), moving-map dashboard.
- `POST /resolve/evidence` + `POST /resolve` — finder co-signed disarm of an active SOS: photo
  evidence upload, then witness-quorum envelope; backend re-verifies every witness signature.
- `POST /control/receipts` — NAN control-receipt ingress (backend-resolve / self-cancel / delivery);
  verified receipts derive ledger events (`SOS_RESOLVED` / `SOS_CANCELLED`).
- `GET /ledger/state/:sosId`, `GET /ledger/events/:sosId`, `GET /ledger/events` (cursor-paginated) —
  SOS lifecycle ledger reads (`POST /ledger/events` is a 501 stub — p2p event transport not enabled).
- `POST /operator/rescuer-certificates` — admin-key issuance of short-lived rescuer certificates.
- `POST /waitlist` — landing-page signup (validated, rate-limited, Resend confirmation email).
- `GET /dashboard` — public self-contained alert map (privacy-degraded coordinates).

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

- **Bun only** for package management and scripts (project preference).
- `/ingest` must **never** persist a frame whose signature it hasn't re-verified. Verification is the
  gate — the same holds for `/control/receipts`, `/resolve`, and `/ledger/events`: every signature is
  re-verified server-side and receipts go through the replay guard.
- Keep `packages/shared` framework-agnostic — it's the contract; don't pull backend-only deps into it.
- The binary frame layouts in `backend/src/mesh/` and `packages/shared/src/mesh/` mirror
  `proto/Payload.kt` / `proto/ControlPayload.kt` on the Android side; if the wire format changes
  there (offsets, sizes, CRC, signing domains), update both or `/ingest` silently rejects valid frames.
- All signatures are **domain-separated** (e.g. `guacamaya.rescuer-cert.v1`, `CONTROL_SIGN_DOMAIN`) —
  never sign or verify bare payload bytes for a new structure; add a new domain constant.
- **Always recompute `id` server-side** (`getRecordId` / `getLocationId`) on ingest — never trust a
  client-supplied id.
- **Locations are frame-derived, never client-trusted.** The canonical, authenticated source of a
  device's position is the **lat/lon inside a verified mesh frame** — `decodeAndVerifyFrame` emits the
  `LocationPoint` and `/ingest` persists it. There is intentionally **no** trusted-JSON location
  ingest endpoint: the same Ed25519 gate that guards records guards positions. `deviceId` is derived
  from the verified pubkey, never supplied by the client. `GET /locations` stays read-only.
