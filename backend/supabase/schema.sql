-- Guacamaya Net — esquema de persistencia del backend.
--
-- Aplicar en el proyecto Supabase (SQL Editor o `supabase db push`).
-- Tabla espejo de `ChannelRecord` (packages/shared/src/types.ts).
-- La dedupe por `id` (SHA-256 del contenido canónico) es la misma que hace el
-- log de gossip en los dispositivos: unión + dedupe por id.

create table if not exists public.channel_records (
  id         text primary key,           -- SHA-256 del contenido canónico
  channel    text        not null,        -- "alertas" | "refugios" | ...
  "timestamp" bigint     not null,        -- unix ms del evento
  ttl        integer     not null,        -- saltos mesh restantes
  author     text        not null,        -- "backend" | "device-<pubkey>"
  verified   boolean     not null default false,
  payload    jsonb       not null,
  sig        text,                        -- firma Ed25519 hex (solo oficiales)
  created_at timestamptz not null default now()
);

-- Consulta principal: registros de un canal desde cierto timestamp, en orden.
create index if not exists channel_records_channel_ts_idx
  on public.channel_records (channel, "timestamp");

-- Sync global ("desde X") para el endpoint de data-mule / ingesta.
create index if not exists channel_records_ts_idx
  on public.channel_records ("timestamp");

-- RLS: el backend usa la service-role key (la salta). Activamos RLS sin
-- políticas para que ningún cliente con la anon key pueda leer/escribir directo.
alter table public.channel_records enable row level security;

-- Location history for the moving-map / trajectory replay feature.
-- Append-only; dedup by `id` (SHA-256 of deviceId:lat:lon:timestamp:accuracy).
create table if not exists public.location_points (
  id          text             primary key,         -- SHA-256 of canonical content → idempotent dedup
  device_id   text             not null,            -- pseudonymous origin device id (not the mule)
  lat         double precision not null,
  lon         double precision not null,
  "timestamp" bigint           not null,            -- unix ms of the GPS fix
  accuracy    double precision,                     -- precision in metres (optional)
  created_at  timestamptz      not null default now()
);

-- Trajectory replay for one device in time order.
create index if not exists location_points_device_ts_idx
  on public.location_points (device_id, "timestamp");

-- Global time window for the moving map ("all points since X").
create index if not exists location_points_ts_idx
  on public.location_points ("timestamp");

-- Service-role key bypasses RLS; no anon client should read trajectories directly.
alter table public.location_points enable row level security;

-- ─── Resolve flow ─────────────────────────────────────────────────────────────
-- A finder co-signs an SOS as resolved. Quorum M-of-N replaces victim
-- confirmation. One receipt per target accumulates witnesses across POSTs.

create table if not exists public.resolve_receipts (
  id                text        primary key,           -- sha256(canonical + sorted witness deviceIds)
  target_sos_id     text        not null,              -- channel_records.id of the original SOS
  target_sos_author text        not null,              -- "device-<pubkey-hex>" of the originator
  status            text        not null,              -- "pending" | "cleared" | "disputed" | "rejected"
  quorum_needed     integer     not null,
  quorum_seen       integer     not null,
  witness_device_ids jsonb      not null default '[]'::jsonb,
  created_at        timestamptz not null default now(),
  cooldown_ends_at  bigint,                            -- unix ms; null once cleared or disputed
  disputed_reason   text
);

create index if not exists resolve_receipts_target_idx
  on public.resolve_receipts (target_sos_id, status);

create index if not exists resolve_receipts_pending_cooldown_idx
  on public.resolve_receipts (cooldown_ends_at)
  where status = 'pending';

create index if not exists resolve_receipts_author_pending_idx
  on public.resolve_receipts (target_sos_author)
  where status = 'pending';

alter table public.resolve_receipts enable row level security;

create table if not exists public.resolve_witnesses (
  receipt_id   text             not null references public.resolve_receipts(id) on delete cascade,
  target_sos_id text            not null,              -- denormalized for the unique constraint + lookup
  device_id    text             not null,              -- "device-<pubkey-hex>"
  pubkey       text             not null,              -- 32-byte Ed25519 hex
  lat          double precision not null,
  lon          double precision not null,
  ts           bigint           not null,              -- unix ms witness observation time
  image_hash   text             not null,              -- sha256 of evidence image bytes
  mac_observation_hashes jsonb  not null default '[]'::jsonb,  -- soft forensic signal
  sig          text             not null,              -- 64-byte Ed25519 hex
  created_at   timestamptz      not null default now(),
  unique (target_sos_id, device_id)
);

create index if not exists resolve_witnesses_target_ts_idx
  on public.resolve_witnesses (target_sos_id, ts);

alter table public.resolve_witnesses enable row level security;
