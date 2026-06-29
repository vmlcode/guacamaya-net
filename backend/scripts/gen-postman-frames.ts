/**
 * Generates real signed GuacaMalla mesh frames for the Postman collection.
 *
 *   bun run backend/scripts/gen-postman-frames.ts
 *
 * Copy the `validFrame` / `validFrame2` / pubkey values into the matching
 * collection variables in backend/postman_collection.json. The frame layout
 * here MUST mirror backend/src/mesh/frame.ts (offsets, CRC, big-endian) — if the
 * wire format changes there, regenerate so /ingest keeps accepting the samples.
 *
 * Uses a deterministic dev key so the frames (and their content-hash ids) are
 * stable across runs — these are test fixtures, never a real device identity.
 */
import * as ed from "@noble/ed25519";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";

// Mirror of backend/src/mesh/frame.ts CRC16-CCITT.
function crc16(data: Uint8Array, offset = 0, length = data.length): number {
  let crc = 0xffff;
  for (let i = offset; i < offset + length; i++) {
    crc ^= (data[i]! & 0xff) << 8;
    crc &= 0xffff;
    for (let b = 0; b < 8; b++) {
      crc = (crc & 0x8000) !== 0 ? (crc << 1) ^ 0x1021 : crc << 1;
      crc &= 0xffff;
    }
  }
  return crc;
}

async function buildFrame(opts: {
  priv: Uint8Array;
  latE7: number;
  lonE7: number;
  tsUnix: number;
  flags: number;
  sosTypeCode: number;
  msgId: number;
  withTtl?: boolean; // prepend a hop-TTL byte (119 B frame)
}): Promise<string> {
  const pub = await ed.getPublicKeyAsync(opts.priv);

  const payload = new Uint8Array(22);
  const dv = new DataView(payload.buffer);
  dv.setInt32(0, opts.latE7, false);
  dv.setInt32(4, opts.lonE7, false);
  dv.setUint32(8, opts.tsUnix, false);
  // node_id = SHA-256(pubkey)[0..3]
  const nodeId = sha256(pub).subarray(0, 4);
  payload.set(nodeId, 12);
  payload[16] = opts.flags;
  payload[17] = opts.sosTypeCode;
  dv.setUint16(18, opts.msgId, false);
  // crc over payload[0..19]
  dv.setUint16(20, crc16(payload, 0, 20), false);

  const sig = await ed.signAsync(payload, opts.priv);

  const body = new Uint8Array(22 + 32 + 64);
  body.set(payload, 0);
  body.set(pub, 22);
  body.set(sig, 54);

  let frame = body;
  if (opts.withTtl) {
    frame = new Uint8Array(1 + body.length);
    frame[0] = 3; // hop TTL
    frame.set(body, 1);
  }
  return Buffer.from(frame).toString("base64");
}

// Deterministic dev key so the frame is stable across runs.
const priv = sha256(new TextEncoder().encode("guacamaya-postman-dev-key"));
const pub = await ed.getPublicKeyAsync(priv);
const tsUnix = 1719500000; // fixed so id is deterministic

// Guatemala City coords (14.6349, -90.5069)
const validFrame = await buildFrame({
  priv,
  latE7: Math.round(14.6349 * 1e7),
  lonE7: Math.round(-90.5069 * 1e7),
  tsUnix,
  flags: 0b0000_0010, // critical bit set
  sosTypeCode: 0, // "medical"
  msgId: 4242,
});

// A second valid frame, different msgId/coords -> different payload -> different id.
const validFrame2 = await buildFrame({
  priv,
  latE7: Math.round(14.636 * 1e7),
  lonE7: Math.round(-90.5081 * 1e7),
  tsUnix: tsUnix + 600,
  flags: 0b0001_0000, // originHopTtl nibble
  sosTypeCode: 3, // "water"
  msgId: 4243,
});

console.log(JSON.stringify({
  pubkeyHex: bytesToHex(pub),
  nodeIdHex: bytesToHex(sha256(pub).subarray(0, 4)),
  validFrame,
  validFrame2,
}, null, 2));
