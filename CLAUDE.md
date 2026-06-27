# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Context rules

Whenever a request touches the **backend** (anything under `backend/`) or the **user flow** (API contracts, data shapes shared between app and backend, authentication, WebSocket events, or location/channel ingestion), read `backend/CLAUDE.md` before responding. It contains the architecture overview, three-layer stack pattern, dedup invariant, environment setup, and all relevant commands for that part of the codebase.

## Monorepo structure

| Package | Path | Purpose |
|---|---|---|
| `@guacamaya/shared` | `packages/shared/` | Types, crypto helpers, mesh merge logic — shared by backend and app |
| `@guacamaya/backend` | `backend/` | Fastify HTTP + WebSocket server, Supabase persistence |
| `app` | `app/` | Expo/React Native mobile app |

Workspaces are managed by Bun. Run `bun install` at the repo root to install all packages.

## Shared package

`packages/shared/` is the source of truth for types (`ChannelRecord`, `LocationPoint`) and the content-hash functions (`getRecordId`, `getLocationId`) used by both the backend and the app. Changes here affect both. Tests for shared utilities live in `packages/shared/src/*.test.ts` and run with `bun test`.
