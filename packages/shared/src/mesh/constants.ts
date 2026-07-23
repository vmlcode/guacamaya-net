/** Wire-format sizes — must stay in sync with net.guacamaya.proto.Payload (Android). */
export const MESH_PAYLOAD_LEN = 22;
export const MESH_PUBKEY_LEN = 32;
export const MESH_SIG_LEN = 64;
export const MESH_FRAME_LEN = MESH_PAYLOAD_LEN + MESH_PUBKEY_LEN + MESH_SIG_LEN; // 118 (upload, TTL stripped)
export const MESH_ON_WIRE_LEN = MESH_FRAME_LEN + 1; // 119 (leading hop TTL)

export const MESH_PAYLOAD_OFF = 0;
export const MESH_PUBKEY_OFF = MESH_PAYLOAD_OFF + MESH_PAYLOAD_LEN;
export const MESH_SIG_OFF = MESH_PUBKEY_OFF + MESH_PUBKEY_LEN;

/**
 * Control frame v1 (delivery / backend-resolve / self-cancel over BLE).
 * Same envelope as the SOS frame: 22 B payload + 32 B pubkey + 64 B signature,
 * with a leading mutable hop-TTL byte on the wire. Must stay byte-identical
 * with net.guacamaya.proto.ControlPayload (Android).
 */
export const CONTROL_PAYLOAD_LEN = 22;
export const CONTROL_FRAME_LEN = CONTROL_PAYLOAD_LEN + MESH_PUBKEY_LEN + MESH_SIG_LEN; // 118 (upload, TTL stripped)
export const CONTROL_ON_WIRE_LEN = CONTROL_FRAME_LEN + 1; // 119 (leading hop TTL)

export const CONTROL_PAYLOAD_OFF = 0;
export const CONTROL_PUBKEY_OFF = CONTROL_PAYLOAD_OFF + CONTROL_PAYLOAD_LEN;
export const CONTROL_SIG_OFF = CONTROL_PUBKEY_OFF + MESH_PUBKEY_LEN;

/**
 * Domain separation for the control-frame Ed25519 signature:
 * sig = Ed25519(sk, UTF8(CONTROL_SIGN_DOMAIN) || payload22).
 * An SOS payload signature can never be replayed as a control frame or
 * vice versa. Mirrored verbatim in net.guacamaya.proto.ControlPayload.
 */
export const CONTROL_SIGN_DOMAIN = "guacamaya.control-frame.v1\n";

export const CONTROL_TYPE_DELIVERY = 0x01;
export const CONTROL_TYPE_BACKEND_RESOLVE = 0x02;
export const CONTROL_TYPE_SELF_CANCEL = 0x03;
export type ControlFrameType =
  | typeof CONTROL_TYPE_DELIVERY
  | typeof CONTROL_TYPE_BACKEND_RESOLVE
  | typeof CONTROL_TYPE_SELF_CANCEL;

/** Max signed lifetime (expiresAt - issuedAt), per type, in unix seconds. */
export const CONTROL_MAX_LIFETIME_SEC: Readonly<Record<ControlFrameType, number>> = {
  [CONTROL_TYPE_DELIVERY]: 30 * 24 * 3600,
  [CONTROL_TYPE_BACKEND_RESOLVE]: 30 * 24 * 3600,
  [CONTROL_TYPE_SELF_CANCEL]: 24 * 3600,
};

/** SOS type codes — mirror of SosType enum on Android. */
export const MESH_SOS_TYPES = [
  "medical",
  "distress",
  "food",
  "water",
  "shelter",
  "fire",
  "violence",
  "other",
] as const;

export type MeshSosType = (typeof MESH_SOS_TYPES)[number];

/**
 * Found frame v1 ("Encontrado") — a finder marks an SOS victim as found.
 * Same envelope as the SOS frame: 22 B payload + 32 B pubkey + 64 B signature,
 * with a leading mutable hop-TTL byte tolerated on the wire. Must stay
 * byte-identical with net.guacamaya.proto.FoundPayload (Android).
 *
 *   off 0..3   latE7         int32 — finder latitude × 1e7
 *   off 4..7   lonE7         int32 — finder longitude × 1e7
 *   off 8..11  tsUnix        u32 unix seconds
 *   off 12..15 targetNodeId  SHA-256(victim pubkey)[0..3]
 *   off 16..17 targetMsgId   u16 — msgId of the SOS session being marked
 *   off 18..19 foundMsgId    u16 — CSPRNG per found report (dedupe entropy)
 *   off 20..21 crc           CRC16-CCITT over bytes 0..19
 */
export const FOUND_PAYLOAD_LEN = 22;
export const FOUND_FRAME_LEN = FOUND_PAYLOAD_LEN + MESH_PUBKEY_LEN + MESH_SIG_LEN; // 118 (upload, TTL stripped)
export const FOUND_ON_WIRE_LEN = FOUND_FRAME_LEN + 1; // 119 (leading hop TTL)

export const FOUND_PAYLOAD_OFF = 0;
export const FOUND_PUBKEY_OFF = FOUND_PAYLOAD_OFF + FOUND_PAYLOAD_LEN;
export const FOUND_SIG_OFF = FOUND_PUBKEY_OFF + MESH_PUBKEY_LEN;

/**
 * Domain separation for the found-frame Ed25519 signature:
 * sig = Ed25519(sk, UTF8(FOUND_SIGN_DOMAIN) || payload22).
 * An SOS or control signature can never be replayed as a found report or
 * vice versa. Mirrored verbatim in net.guacamaya.proto.FoundPayload.
 */
export const FOUND_SIGN_DOMAIN = "guacamaya.found-frame.v1\n";

/** Default proximity gate: finder within this many meters of the SOS's last
 * reported point → "near / likely_found"; further → "far / possible_false_positive".
 * Backend default; env-overridable via FOUND_PROXIMITY_METERS. */
export const FOUND_PROXIMITY_NEAR_METERS = 100;
