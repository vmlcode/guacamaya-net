#!/usr/bin/env python3
"""
SOSNet tamper demo.

Generates one valid SOSNet BLE frame and one with a single bit flipped in the
22-byte payload, then verifies the math:

    python3 scripts/tamper_test.py

Output: a JSON file at /tmp/sosnet_test_frames.json with two hex blobs:

    {
      "node_id_hex":   "...",            # 8 hex chars, SHA-256(pubkey)[0:4]
      "valid_frame":   "<118-byte hex>",  # 22 payload + 32 pubkey + 64 sig
      "tampered_frame":"<118-byte hex>",  # same, one payload bit flipped
      "expected": {
        "valid":    "verify=true",
        "tampered": "verify=false (FloodRouter DROP)"
      }
    }

Push to the phone and load via the app's Debug > Load test frame menu:

    adb push /tmp/sosnet_test_frames.json /sdcard/sosnet_test_frames.json

Requires: pip install cryptography
"""
from __future__ import annotations

import binascii
import hashlib
import json
import os
import struct
import sys
import time
from pathlib import Path

try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import (
        Ed25519PrivateKey,
        Ed25519PublicKey,
    )
    from cryptography.hazmat.primitives.serialization import (
        Encoding,
        PublicFormat,
    )
except ImportError:
    sys.exit("ERROR: pip install cryptography")


# ---------- CRC16-CCITT (poly 0x1021, init 0xFFFF, no reflection, no final XOR) ----

def crc16_ccitt(data: bytes) -> int:
    crc = 0xFFFF
    for byte in data:
        crc ^= byte << 8
        crc &= 0xFFFF
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


# ---------- SOSNet 22-byte payload ---------------------------------------------------

def build_payload(lat_e7: int, lon_e7: int, ts_unix: int, node_id: bytes,
                  has_heavy: bool, critical: bool, battery: int, hop_ttl: int,
                  sos_type: int, msg_id: int) -> bytes:
    assert len(node_id) == 4
    assert 0 <= battery <= 3
    assert 0 <= hop_ttl <= 15
    flags = (1 if has_heavy else 0) | (2 if critical else 0) | ((battery & 3) << 2) | ((hop_ttl & 0xF) << 4)
    body = struct.pack(">iiI", lat_e7, lon_e7, ts_unix) + node_id + bytes([flags, sos_type]) + struct.pack(">H", msg_id)
    assert len(body) == 20
    crc = crc16_ccitt(body)
    return body + struct.pack(">H", crc)


# ---------- Ed25519 sign/verify (mirrors Signer.kt) ---------------------------------

def sign(priv: Ed25519PrivateKey, payload22: bytes) -> bytes:
    assert len(payload22) == 22
    return priv.sign(payload22)


def verify(pub: Ed25519PublicKey, payload22: bytes, sig: bytes) -> bool:
    if len(payload22) != 22 or len(sig) != 64:
        return False
    try:
        pub.verify(sig, payload22)
        return True
    except Exception:
        return False


# ---------- Demo -------------------------------------------------------------------

def main() -> int:
    out_path = Path("/tmp/sosnet_test_frames.json")

    # 1. Generate origin identity.
    priv = Ed25519PrivateKey.generate()
    pub = priv.public_key()
    pub_bytes = pub.public_bytes(Encoding.Raw, PublicFormat.Raw)
    assert len(pub_bytes) == 32
    node_id = hashlib.sha256(pub_bytes).digest()[:4]

    # 2. Build a real-looking payload (Mexico City, medical, critical, heavy available).
    ts = int(time.time())
    payload = build_payload(
        lat_e7=19_432_600,
        lon_e7=-99_133_200,
        ts_unix=ts,
        node_id=node_id,
        has_heavy=True,
        critical=True,
        battery=3,
        hop_ttl=15,
        sos_type=0,        # medical
        msg_id=0x2A7C,
    )
    assert len(payload) == 22

    # 3. Sign.
    sig = sign(priv, payload)
    assert len(sig) == 64

    # 4. Frame = payload + pubkey + sig (mirrors BLE service-data layout).
    valid_frame = payload + pub_bytes + sig
    assert len(valid_frame) == 118

    # 5. Tamper: flip one bit in the latitude byte.
    tampered_payload = bytearray(payload)
    tampered_payload[5] ^= 0x01
    tampered_payload = bytes(tampered_payload)
    tampered_frame = tampered_payload + pub_bytes + sig
    assert len(tampered_frame) == 118

    # 6. Verify both — print the math the jury cares about.
    valid_ok = verify(pub, valid_frame[0:22], valid_frame[22 + 32:])
    tampered_ok = verify(pub, tampered_frame[0:22], tampered_frame[22 + 32:])

    # 7. Write JSON.
    out = {
        "node_id_hex": node_id.hex(),
        "valid_frame": valid_frame.hex(),
        "tampered_frame": tampered_frame.hex(),
        "expected": {
            "valid": f"verify={valid_ok}",
            "tampered": f"verify={tampered_ok} (FloodRouter DROP: signature invalid)",
        },
        "notes": [
            "valid_frame and tampered_frame differ by exactly one bit in payload byte 5.",
            "Same pubkey, same signature — only the signed bytes differ.",
            "Ed25519 is EUF-CMA: the signature does not verify against the modified bytes.",
        ],
    }
    out_path.write_text(json.dumps(out, indent=2))
    print(f"wrote {out_path}")
    print(f"node_id    = {node_id.hex()}")
    print(f"valid      = {len(valid_frame)} B  verify={valid_ok}")
    print(f"tampered   = {len(tampered_frame)} B  verify={tampered_ok}")
    print()
    print("Push to the phone for the in-app tamper demo:")
    print(f"  adb push {out_path} /sdcard/sosnet_test_frames.json")
    return 0 if (valid_ok and not tampered_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
