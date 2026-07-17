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
