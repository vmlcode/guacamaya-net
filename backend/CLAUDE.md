# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# From the repo root
bun run dev:backend          # start backend with hot-reload (bun --watch)
bun run build                # typecheck shared + backend (no build artifacts)
bun run typecheck            # alias for build
bun test                     # run all tests (packages/shared)
bun test packages/shared/src/locations.test.ts  # run a single test file

# From backend/
bun run dev                  # same as above, local
bun run start                # production start (no watch)
bun run build                # backend typecheck only
bun run --cwd backend tsc --noEmit  # type-check only
```

Tests live in `packages/shared/src/` (Bun test runner). There are no backend-specific test files yet; integration tests hit the in-memory store path (Supabase not configured in test env).

## Environment

Copy `.env.example` → `.env`. Without Supabase vars the data layer falls back to in-memory stores.
Set `GUACAMAYA_ADMIN_KEY` (and optionally `GUACAMAYA_READ_KEY`, `GUACAMAYA_WS_KEY`) before
production — `NODE_ENV=production` refuses to start without an admin key. In dev, ephemeral keys
are generated and logged if unset. Generate keys with `bun run keygen` from the repo root.

## Security

- **CORS** — `CORS_ORIGINS` (comma-separated); never `*` in production.
- **Official records** — `POST /channels/:id/records` requires `X-Api-Key` / `Authorization: Bearer`
  matching `GUACAMAYA_ADMIN_KEY`; only official channels (`alertas`, `refugios`, `ayuda-medica`).
- **Location history** — `GET /locations` requires the read key (`GUACAMAYA_READ_KEY` or admin).
- **WebSocket** — `/ws?token=<key>` or `Sec-WebSocket-Protocol: guacamalla.<key>`; uses
  `GUACAMAYA_WS_KEY` or read key.
- **Ingest** — zero-trust frame verification unchanged; batch capped at `MAX_INGEST_BATCH` (default 200)
  with per-route rate limit (30/min). `@fastify/helmet` enabled.
- **Timing-safe** API key comparison via `crypto.timingSafeEqual`.

## Architecture

This is a **Bun + Fastify** HTTP/WebSocket server. The entry point is `src/index.ts`, which registers plugins (CORS, rate-limit 100 req/min) and two route plugins, then starts the WS upgrade listener.

### Two feature domains, same pattern

Each domain follows the same three-layer stack:

```
routes.ts  (Fastify plugin)
    ↓
*Repo.ts   (Supabase when configured, in-memory fallback otherwise)
    ↓
store.ts   (append-only Map, dedup by `id`)
```

**`channels/`** — emergency broadcast channels (alertas, refugios, etc.)  
**`locations/`** — GPS trajectory history for the moving-map dashboard. **Read-only at the HTTP edge**
(`GET /locations`): points are *ingested* from zero-trust-verified mesh frames in `POST /ingest`, not
from a trusted JSON endpoint. `mesh/frame.ts` decodes the lat/lon out of each signed payload and
`decodeAndVerifyFrame` returns a `LocationPoint` (deviceId = origin pubkey) that `channels/routes.ts`
persists via `locationsRepo` and broadcasts via `broadcastLocation`. The `store.ts`/`*Repo.ts` layers
still follow the three-layer pattern below.

### Dedup invariant

Every record/point has an `id` = SHA-256 of its canonical content (`getRecordId` / `getLocationId` in `packages/shared/src/crypto.ts`). This is the same dedup strategy used by the mesh gossip log on devices. Any number of data mules can upload overlapping batches; the backend ignores re-uploads via `upsert onConflict:"id" ignoreDuplicates:true`. **Always recompute `id` server-side** on ingest — never trust the client-supplied value.

### WebSocket server (`ws/server.ts`)

Uses the raw `ws` library attached to Fastify's underlying HTTP server via the `upgrade` event (path `/ws`). Clients send `{ type: "subscribe", channel: "<name>" }` to opt in to a logical channel (any string, including `"locations"` for the moving map). Two broadcast helpers: `broadcastRecord(record)` fans out to channel subscribers; `broadcastLocation(point)` fans out to `"locations"` subscribers.

### Cryptographic signing (`crypto/`)

`keys.ts` loads `BACKEND_PRIVATE_KEY` (hex) from env or generates a random key. `signer.ts` signs `ChannelRecord`s with Ed25519 — the hash signed is the `getRecordId` hex, re-interpreted as bytes. Only official backend-created channel records are signed; device-uploaded records are stored as-is with `verified: false`.

### Shared package (`packages/shared/`)

Imported as `@guacamaya/shared` (workspace alias). Exports:
- `types.ts` — `ChannelRecord`, `LocationPoint`
- `crypto.ts` — `getRecordId`, `getLocationId`, `verifyRecordSignature`
- `merge.ts` — `mergeLogs` (union + dedup for mesh gossip sync)

### Supabase schema (`supabase/schema.sql`)

Two tables: `channel_records` and `location_points`, both with `id text primary key` and RLS enabled (service-role key bypasses it). Apply manually via Supabase SQL Editor or `supabase db push`.
