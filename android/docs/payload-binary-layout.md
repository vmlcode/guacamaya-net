# SOSNet — Payload Binary Layout

Over-the-air frame for the BLE Extended Advertising (ADV_EXT_IND) control plane. Four blocks travel together as BLE service data: a 1 B unsigned hop TTL, a 22 B application payload, the 32 B Ed25519 public key of the origin (so any receiver can verify without a registry), and the 64 B Ed25519 signature over the payload.

Total: **119 B**, well within the BLE 5 extended ADV limit (≤ 254 B). Does not fit legacy 31 B ADV — by design, since extended ADV is required for the security guarantees.

The hop TTL is deliberately **outside the signed payload**: each relay decrements it, and if it lived inside the signed 22 B the origin's signature would break at the next hop (re-signing is impossible — `node_id` is bound to the origin's pubkey). The payload's `flags.hop_ttl` nibble is therefore the *origin's initial* value (informational); the authoritative live hop budget is the leading unsigned byte.

---

## 22 B application payload

| Offset | Size | Field | Encoding | Notes |
|---|---|---|---|---|
| 0 | 4 | `lat_e7` | int32, big-endian | Latitude × 1e7 (±90° fits in 31 signed bits). Resolution ≈ 1 cm. |
| 4 | 4 | `lon_e7` | int32, big-endian | Longitude × 1e7. ±180° fits in 32 signed bits. |
| 8 | 4 | `ts_unix` | uint32, big-endian | Seconds since 1970-01-01. Wraps 2106-02-07. |
| 12 | 4 | `node_id` | 4 raw bytes | First 4 B of SHA-256(pubkey). Truncated identifier. |
| 16 | 1 | `flags` | bitfield | See below. |
| 17 | 1 | `sos_type` | uint8 enum | See below. |
| 18 | 2 | `msg_id` | uint16, big-endian | Per-node sequence number; rotates. Used for dedupe. |
| 20 | 2 | `crc16` | uint16, big-endian | CRC16-CCITT (poly 0x1021, init 0xFFFF) over bytes 0..19. Cheap reject before sig verify. |

### `flags` bitfield (byte 16)

```
bit 7 6 5 4 3 2 1 0
    └─┴─┴─┴─┘ │ │ └─ bit 0: has_heavy   (1 = Wi-Fi Aware heavy payload available)
              │ └─── bit 1: critical    (1 = immediate life threat)
              └───── bits 2–3: battery bucket (0=empty … 3=full)
   bits 4–7: hop_ttl (0–15, set to 15 at origin)
```

### `sos_type` enum (byte 17)

```
0 = medical
1 = distress (generic)
2 = food
3 = water
4 = shelter
5 = fire
6 = violence
7 = other
8…255 = reserved
```

---

## 32 B origin public key

The Ed25519 public key of the origin node. Travels in every frame so any receiver can verify the signature without a central registry, key server, or prior exchange. This is essential for a connectionless mesh: there is no out-of-band channel to learn keys.

Verification binds the pubkey to the 4-byte `node_id` in the payload: `node_id == SHA-256(pubkey)[0..4]`. If they disagree, the frame is dropped.

## 64 B Ed25519 signature

Concatenated after the 32 B pubkey inside the same BLE service-data block.

- Algorithm: Ed25519 (RFC 8032).
- Signed scope: bytes 0..21 of the application payload (22 B total; CRC16 included). The pubkey and signature themselves are NOT signed.
- Verification: `Ed25519Verify(pubkey, payload[0..21], sig)`.

See [`crypto.md`](crypto.md) for key handling and the rejection cascade.

---

## BLE service data layout

```
+--------------------------------------------------+
| Service UUID (16 B, 128-bit, little-endian on wire) |
+--------------------------------------------------+
| Hop TTL (1 B, unsigned, mutable — decremented per relay) |
+--------------------------------------------------+
| Payload bytes 0..21 (22 B)                       |
+--------------------------------------------------+
| Origin pubkey bytes 0..31 (32 B)                 |
+--------------------------------------------------+
| Signature bytes 0..63 (64 B)                     |
+--------------------------------------------------+
Total = 119 B service data (excluding Service UUID header)
```

Byte offsets within the service-data blob (see `ble/BleConfig.kt`): TTL @ 0, payload @ 1, pubkey @ 23, signature @ 55. A relay forwards the payload/pubkey/signature unchanged and rebroadcasts with `ttl − 1`; at 0 it stops relaying (the frame is still stored locally so the receiving user sees it).

The 128-bit Service UUID is the constant defined in `ble/BleConfig.kt`:

```
SOSNET_SERVICE_UUID = "8d3d0001-2a1b-4c8e-9c0f-1234567890ab"
```

(Placeholder value; final value committed in `BleConfig.kt`.)

---

## Heavy-payload correlation

When `flags.has_heavy = 1`, the NAN service name on the data plane is derived from the BLE frame:

```
nan_service_name = "sosnet::" + hex(node_id[0..4]) + "::" + hex(msg_id)
```

A subscriber that sees `has_heavy=1` in the BLE frame can immediately subscribe to that NAN service name to pull the heavy bytes — no extra discovery round-trip.

---

## Worked example

Origin at lat 19.4326, lon −99.1332 (Mexico City), type medical, critical, has a heavy map tile, full battery, msg_id 0x2a7c, ts = 0x6857E200 (2025-06-13 18:00:00 UTC).

```
lat_e7   = 194326000        → 0x0B991A60
lon_e7   = -99133200        → 0xFA1740F0  (two's complement)
ts_unix  = 1750255200       → 0x68229930
node_id  = (sample)         → 0xA1B2C3D4
flags    = has_heavy|crit|batt=3|ttl=15
                            → 0b11110111 = 0xF7
sos_type = medical = 0      → 0x00
msg_id   = 0x2A7C
crc16    = (computed)       → 0xXXXX
```

Concatenated (CRC placeholder):

```
0B 99 1A 60  FA 17 40 F0  68 22 99 30  A1 B2 C3 D4
F7 00 2A 7C  XX XX
```

Followed by the 32-byte Ed25519 origin pubkey and the 64-byte signature over the 22 bytes above.
