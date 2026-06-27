# SOSNet

Connectionless L2 messaging mesh for SOS dissemination. Phones behave as radio
beacons, not peers: **no pairing, no handshake, no credentials exchange**.
Two planes carry the data — BLE for discovery + telemetry, Wi-Fi Aware (NAN)
for medium and heavy payloads — and authenticity is relocated from the link
layer to the application layer via **Ed25519 signatures**.

---

## Thesis

Commercial messaging apps open dedicated channels between two endpoints and
authenticate at the link layer (pairing, WPA2, …). SOSNet discards that model.
The link is intentionally open — this is a public SOS, anyone in range should
receive and relay it. Trust lives at the payload layer: every 22-byte frame is
signed Ed25519, and any tampering breaks the signature so the next hop silently
drops the packet.

The result is an asynchronous, opportunistic, connectionless mesh. Phones emit
structured bursts into the air and listen to what floats by. No connection to
drop, no credential to leak, no infrastructure to fail.

---

## Architecture

| Plane | Radio | Payload size | Standard |
|---|---|---|---|
| Control | BLE GAP Broadcaster / Observer | 22 B + 32 B pubkey + 64 B sig (118 B service data) | BLE 5 Extended Advertising (`ADV_EXT_IND`, 1M/Coded PHY) |
| Data (light) | Wi-Fi Aware (NAN) | ≤ 255 B | NAN Service Discovery Action Frame |
| Data (heavy) | Wi-Fi Aware (NAN) | > 255 B | NAN Data Path (NDP), auto-negotiated, ephemeral |

See [`docs/protocol-flows.md`](docs/protocol-flows.md) for the seven formal
flows with sequence diagrams and FSMs.

---

## Build the APK

### Prerequisites

- **JDK 17** (`java --version` shows 17.x)
- **Android SDK** with `platform-android-34` and `build-tools;34.0.0`
  - Easiest path: install **Android Studio**, which bundles both. On first
    project open it will pull the rest automatically.
- **Python 3** (only used by the optional tamper demo script)

### Option A — Android Studio (recommended)

1. Open this folder in Android Studio: `File → Open → SOSNet`.
2. Wait for Gradle sync (first run downloads dependencies).
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`.
4. APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Command line

```bash
./gradlew :app:assembleDebug
```

APK at `app/build/outputs/apk/debug/app-debug.apk`.

> If `./gradlew` complains the wrapper is missing, run
> `gradle wrapper --gradle-version 8.7` once (system `gradle` required), then retry.

### Install on a phone (API 26+, Wi-Fi Aware capable)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Two phones recommended for the demo (one broadcaster, one observer).

---

## Demo runner (one-shot build + install)

```bash
./scripts/demo.sh install     # build APK + adb install on the connected phone
./scripts/demo.sh tamper      # generate tampered frame, push to /sdcard
./scripts/demo.sh logcat      # stream colorized logcat from the phone
```

Full narrative with timing — see [`docs/demo-runbook.md`](docs/demo-runbook.md).

### Verify the tamper math on host

```bash
python3 scripts/tamper_test.py
```

Prints `verify=True` for the valid frame and `verify=False` for the tampered
one (one bit flipped in payload byte 5). Writes `/tmp/sosnet_test_frames.json`.

---

## Documentation

- [`docs/protocol-flows.md`](docs/protocol-flows.md) — the seven formal protocol
  flows (mermaid sequence diagrams + FSMs).
- [`docs/payload-binary-layout.md`](docs/payload-binary-layout.md) — byte map of
  the 118 B BLE service-data frame.
- [`docs/crypto.md`](docs/crypto.md) — Ed25519 signing scope, key handling,
  threat model.
- [`docs/demo-runbook.md`](docs/demo-runbook.md) — 90 s demo script for the
  operator.

---

## Repo layout

```
SOSNet/
├── README.md
├── docs/                       # spec — the jury deliverable
├── app/                        # Android Studio Gradle project
│   └── src/main/kotlin/org/sosnet/
│       ├── crypto/             # Ed25519 identity, sign/verify
│       ├── proto/              # 22 B payload codec + CRC16
│       ├── ble/                # Broadcaster (ADV_EXT_IND, 1M/Coded) + Observer
│       ├── aware/              # NAN messenger + NAN Data Path
│       ├── mesh/               # FloodRouter, dedupe, persistence
│       ├── service/            # Foreground service holding radios alive
│       └── ui/                 # Jetpack Compose + OSMDroid map
├── scripts/
│   ├── demo.sh                 # build + install + tamper + logcat helper
│   ├── tamper_test.py          # bit-flip injection for sig-break demo
│   └── logcat_pretty.py        # colorized logcat filter
├── gradlew / gradlew.bat
├── gradle/                     # wrapper + version catalog (libs.versions.toml)
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Platform

- **Min SDK**: 26 (Wi-Fi Aware since Android 8.0).
- **Target SDK**: 34.
- **Kotlin**: 1.9.x. **AGP**: 8.5.x. **Compose BOM**: 2024.06.
- **Crypto**: BouncyCastle 1.78.1 (Ed25519 below API 33).
- **Map**: OSMDroid (offline-capable).

---

## License

TBD before submission.
