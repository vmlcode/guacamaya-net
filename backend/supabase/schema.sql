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
