/** Wire-format sizes — must stay in sync with net.guacamaya.proto.Payload (Android). */
export const MESH_PAYLOAD_LEN = 22;
export const MESH_PUBKEY_LEN = 32;
export const MESH_SIG_LEN = 64;
export const MESH_FRAME_LEN = MESH_PAYLOAD_LEN + MESH_PUBKEY_LEN + MESH_SIG_LEN; // 118 (upload, TTL stripped)
export const MESH_ON_WIRE_LEN = MESH_FRAME_LEN + 1; // 119 (leading hop TTL)

export const MESH_PAYLOAD_OFF = 0;
export const MESH_PUBKEY_OFF = MESH_PAYLOAD_OFF + MESH_PAYLOAD_LEN;
export const MESH_SIG_OFF = MESH_PUBKEY_OFF + MESH_PUBKEY_LEN;

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
