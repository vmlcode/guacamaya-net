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
