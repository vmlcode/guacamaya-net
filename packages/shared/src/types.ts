export type ChannelId =
  | "alertas"
  | "refugios"
  | "ayuda-medica"
  | "estoy-bien"
  | "solicito-ayuda"
  | (string & {});

export interface ChannelRecord {
  id: string;        // SHA-256 del contenido canónico → clave de dedupe
  channel: ChannelId;
  timestamp: number; // unix ms — momento del evento
  ttl: number;       // saltos mesh restantes; 0 = no reenviar
  author: string;    // "backend" | "device-<pubkey-hex>"
  verified: boolean; // true = firmado y verificado por el backend
  payload: unknown;
  sig?: string;      // firma Ed25519 hex (solo registros oficiales)
}

export type RecordMap = Map<string, ChannelRecord>;

export interface LocationPoint {
  id: string;         // SHA-256 of canonical content → dedup key
  deviceId: string;   // pseudonymous id of the origin device (not the mule)
  lat: number;        // latitude  [-90, 90]
  lon: number;        // longitude [-180, 180]
  timestamp: number;  // unix ms of the GPS fix
  accuracy?: number;  // precision in meters (optional)
}
