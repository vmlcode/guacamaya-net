# SOSNet — Demo Runbook (90 s, two phones)

The narrative the operator reads aloud while the demo runs. Each step links to
the Flow it demonstrates. Jury sees the sequence diagrams side-by-side on a
slide; operator's screen mirror matches each step.

## Setup (before the clock starts)

```bash
# Host: build + install on both phones
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk      # phone A
adb install -r app/build/outputs/apk/debug/app-debug.apk      # phone B

# Pre-generate the tampered frame
python3 scripts/tamper_test.py
adb push /tmp/sosnet_test_frames.json /sdcard/sosnet_test_frames.json
```

Open `nRF Connect` on a third phone or laptop — used to prove no pairing prompt.

## 0:00 — Thesis (10 s)

> "Phones behave as radio beacons, not peers. No pairing, no handshake. The link is intentionally open because this is a public SOS; authenticity lives at the application layer via Ed25519 signatures."

Show: `docs/protocol-flows.md` slide 1 (architecture table).

## 0:10 — Flow 0: Identity (5 s)

Open app on Phone A. The card shows `node_id = …`. Tap **Broadcast SOS**.

> "On first launch the app generated an Ed25519 keypair. The first four bytes of the SHA-256 of the public key are the on-wire node identifier. No registry, no server."

Show: Phone A screen.

## 0:15 — Flow 1: BLE Broadcaster (10 s)

Open `nRF Connect` on the third device. Highlight:

- SOSNet Service UUID `8d3d0001-2a1b-4c8e-9c0f-1234567890ab`
- 118-byte service-data block (22 + 32 + 64)
- **No pairing prompt anywhere on screen.**

> "The phone radiates a 118-byte frame on BLE 5 Extended Advertising on channels 37, 38, and 39. Anyone in range sees it; no ACK, no handshake."

## 0:25 — Flow 2: Observe + verify (15 s)

Open app on Phone B. Tap **Observe**. Within ~5 seconds:

- Red pin appears on Phone B's map at Phone A's coordinates.
- Logcat line (colorized via `scripts/logcat_pretty.py`):
  ```
  OK  node=6f720ded msg=10633 type=DISTRESS rssi=-58 fresh=true hops_left=15
  ```

> "The BLE stack scans with `legacy=false` and filters SOSNet UUIDs in software — hardware filters would drop the 118-byte extended service-data on some chips (e.g. Qualcomm on Android 11). The app verified the CRC, the ts window, the SHA-256 binding of pubkey to node_id, and finally the Ed25519 signature. The frame is authentic; the relay stores it and rebroadcasts the exact 118 bytes."

## 0:40 — Flow 6: Battery duty cycle (5 s)

Lock Phone B. Five seconds of silence. Unlock. Map still updates.

> "Foreground service, type connectedDevice. BLE Observer uses low-latency extended scan with software UUID filter — receiver stays passive without pairing."

## 0:45 — Tamper demo (15 s)

Run on host:

```bash
adb logcat -c            # clear
python3 scripts/tamper_test.py
```

Push the tampered frame JSON to Phone B's `/sdcard/` and tap **Load test frame (tampered)** in the app's Debug menu. Logcat shows:

```
DROP: signature invalid
```

> "We flipped exactly one bit in the payload. The signature is now mathematically broken. The relay dropped it; the store is unaffected. This is what makes the mesh trustworthy without pairing — anyone can relay, no one can forge."

## 1:00 — Flow 4/5: Wi-Fi Aware (15 s)

On Phone A tap **Send heavy payload** (sends a 50 KB map tile via NAN). Phone B shows the transfer:

- ≤ 255 B: NAN Service Discovery Action Frame, no pairing.
- > 255 B: NAN Data Path auto-negotiated by firmware; ≈ 50 Mbps; torn down the instant the buffer drains.

> "Wi-Fi Aware splits the difference. Mid-size frames ride MAC-layer discovery — no IP, no auth. Heavy frames bring up a NAN Data Path, transfer at 50 Mbps, and self-destruct."

## 1:15 — Closing (15 s)

> "Connectionless L2 messaging. Asynchronous, opportunistic, no infrastructure. Security at the application layer. Built on a weekend."

Show: `docs/protocol-flows.md` slide 10 (Why no pairing?).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| No pin on Phone B | Settings → Location On, BT On, Wi-Fi On; on API 31+ grant Nearby Devices / BT Scan |
| `SecurityException: Need BLUETOOTH` on API ≤ 30 | Reinstall APK — manifest must declare `BLUETOOTH` + `BLUETOOTH_ADMIN` with `maxSdkVersion=30` |
| A broadcasts, B sees 0 frames (extended ADV) | Broadcaster must use secondary PHY **Coded** (not 2M); observer `legacy=false` |
| `Observer.create failed` | Bluetooth off. Toggle in system settings. |
| `Aware attach failed` | Wi-Fi off, or device lacks `android.hardware.wifi.aware`. Try Pixel 3+. |
| No pin after tamper | Expected — the frame was dropped. |
