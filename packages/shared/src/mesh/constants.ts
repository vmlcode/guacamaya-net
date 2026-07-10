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
