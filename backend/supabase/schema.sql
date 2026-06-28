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
