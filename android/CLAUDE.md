# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**GuacaMalla Net** — a connectionless L2 mesh for broadcasting SOS distress signals when cell
infrastructure is down. Pure native Android, Kotlin + Jetpack Compose. Phones act as **radio beacons,
not peers**: no pairing, no handshake, no credential exchange. Authenticity lives on the application
layer via Ed25519 signatures.

The Android app is **serverless by design**. The optional Bun backend (`POST /ingest`) is never a
hard dependency.

Package: `net.guacamaya`. Open **`android/`** (not the repo root) in Android Studio.

## Build / test / run

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "net.guacamaya.proto.PayloadTest"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Demo helper: `./scripts/demo.sh install|logcat|tamper`

## Architecture

- **119-byte BLE frame**: `1 B hop TTL + 22 B signed payload + 32 B pubkey + 64 B signature`
- **Reject cascade** (`mesh/FloodRouter.kt`): pubkey bind → CRC → ±300s skew → Ed25519 verify
- **Identity** (`crypto/Identity.kt`): Ed25519 in Android Keystore
- **Service** (`service/GuacamayaForegroundService.kt`): foreground BLE broadcast + observe
- **Wire constants** must match `packages/shared/src/mesh/` on the backend

## Module map (`app/src/main/kotlin/net/guacamaya/`)

`proto/`, `crypto/`, `ble/`, `aware/`, `mesh/`, `service/`, `ui/`

## Conventions

- Verify is always the last gate — never persist or relay before the full cascade passes.
- BLE Observer filters service UUID **in software**, not hardware `ScanFilter`.
- BLE service UUID in `BleConfig` is still a placeholder for production.
