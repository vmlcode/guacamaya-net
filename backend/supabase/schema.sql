-- GuacaMalla Net — esquema de persistencia del backend.
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

-- ─── Ledger distribuido firmado ───────────────────────────────────────────────
-- Cadena por sosId (NO blockchain global). Cada SOS_CREATED inicia una cadena
-- independiente; RESOLVED/CANCELLED/EXPIRED/KEY_REVOKED encadenan por
-- previous_sos_event_hash. La reconciliación (reconcileState en packages/shared)
-- resuelve bifurcaciones por compareAuthority (rank → quorumCount → eventId).
-- chain_status: 'attached' (cadena válida desde SOS_CREATED), 'orphan'
-- (previous_sos_event_hash apunta a evento ausente), 'conflict' (perdió la
-- bifurcación contra otro attached). Eventos inválidos van a
-- ledger_rejected_events aparte (DoS de reserva de event_id falso).

create table if not exists public.ledger_events (
  event_id                text        primary key
    check (event_id ~ '^[0-9a-f]{64}$'),
  sos_id                  text        not null
    check (sos_id ~ '^[0-9a-f]{64}$'),
  type                    text        not null check (type in (
    'SOS_CREATED', 'SOS_RESOLVED', 'SOS_CANCELLED', 'SOS_EXPIRED', 'KEY_REVOKED'
  )),
  previous_sos_event_hash text
    check (previous_sos_event_hash is null
           or previous_sos_event_hash ~ '^[0-9a-f]{64}$'),
  issued_at               bigint      not null,           -- ms; emisión del evento (auditable, no orden)
  issuer_public_key       text        not null
    check (issuer_public_key ~ '^[0-9a-f]{64}$'),
  payload                 jsonb       not null,           -- objeto canónicamente hashable
  payload_hash            text        not null
    check (payload_hash ~ '^[0-9a-f]{64}$'),              -- sha256(canonicalJsonBytes(payload))
  proof                   jsonb       not null,
  event_signature         bytea,                          -- 64 B Ed25519; null solo en SOS_CREATED
  chain_status            text        not null
    check (chain_status in ('attached', 'orphan', 'conflict')),
  received_at             bigint      not null
    default (extract(epoch from now()) * 1000)::bigint
);

-- Reconstrucción de cadena por sosId (padre→hijos).
create index if not exists idx_ledger_events_sos_id_issued_at
  on public.ledger_events (sos_id, issued_at);

-- Feed paginado con cursor estable (receivedAt + eventId).
create index if not exists idx_ledger_events_received_at_event_id
  on public.ledger_events (received_at, event_id);

-- Un solo SOS_CREATED por sosId. Insertar un segundo rompe la cadena.
create unique index if not exists uq_ledger_created_per_sos
  on public.ledger_events (sos_id) where type = 'SOS_CREATED';

alter table public.ledger_events enable row level security;
create policy "service_role all ledger_events" on public.ledger_events
  for all using (auth.role() = 'service_role');

-- Estado materializado por sosId. Se reconcilia desde ledger_events vía
-- reconcileState; null mientras la cadena esté rota (orphans puros).
create table if not exists public.sos_state (
  sos_id          text        primary key
    check (sos_id ~ '^[0-9a-f]{64}$'),
  current_type    text        not null check (current_type in (
    'active', 'resolved', 'cancelled', 'expired'
  )),
  terminal        boolean     not null,
  last_event_id   text        not null
    check (last_event_id ~ '^[0-9a-f]{64}$'),
  last_event_at   bigint      not null,
  witness_count   integer     not null default 0
    check (witness_count >= 0),
  retention_until bigint,                             -- null = sin expiración programada
  updated_at      bigint      not null
    default (extract(epoch from now()) * 1000)::bigint
);

alter table public.sos_state enable row level security;
create policy "service_role all sos_state" on public.sos_state
  for all using (auth.role() = 'service_role');

-- Eventos criptográficamente inválidos. No comparten PK con ledger_events:
-- un atacante podría reservar un event_id falso y bloquear el legítimo.
-- raw_event es solo auditoría forense.
create table if not exists public.ledger_rejected_events (
  rejection_id     uuid     primary key default gen_random_uuid(),
  claimed_event_id text
    check (claimed_event_id is null or claimed_event_id ~ '^[0-9a-f]{64}$'),
  sos_id           text
    check (sos_id is null or sos_id ~ '^[0-9a-f]{64}$'),
  reason           text     not null,
  raw_event        jsonb    not null,
  received_at      bigint   not null
    default (extract(epoch from now()) * 1000)::bigint
);

create index if not exists idx_ledger_rejected_claimed_event_id
  on public.ledger_rejected_events (claimed_event_id)
  where claimed_event_id is not null;

create index if not exists idx_ledger_rejected_received_at
  on public.ledger_rejected_events (received_at);

alter table public.ledger_rejected_events enable row level security;
create policy "service_role all ledger_rejected_events" on public.ledger_rejected_events
  for all using (auth.role() = 'service_role');

-- ─── Replay guard de tramas de control ────────────────────────────────────────
-- Una trama de control v1 (delivery / backend-resolve / self-cancel) ya verificada
-- se admite una sola vez por firma (signature_hex = hex de los 64 B Ed25519), aunque
-- llegue reenviada por múltiples mules o saltos. expires_at permite la purga periódica
-- de entradas vencidas (la trama en sí lleva expiresAt firmado). Es la contraparte
-- persistente del DedupeCache LRU en memoria de Android (Fase 2/4).

create table if not exists public.control_replay (
  signature_hex   text    primary key
    check (signature_hex ~ '^[0-9a-f]{128}$'),             -- 64 B Ed25519 hex
  frame_type      smallint not null,                        -- 0x01 delivery | 0x02 backend-resolve | 0x03 self-cancel
  issuer_node_id  text    not null,                         -- sha256(issuer pubkey)[0..3] hex — namespace de dedupe
  ctrl_msg_id     integer not null,                         -- u16 CSPRNG de la trama
  expires_at      bigint  not null,                         -- unix ms; para purga
  received_at     bigint  not null
    default (extract(epoch from now()) * 1000)::bigint
);

-- Purga periódica de entradas vencidas.
create index if not exists control_replay_expires_at_idx
  on public.control_replay (expires_at);

alter table public.control_replay enable row level security;
create policy "service_role all control_replay" on public.control_replay
  for all using (auth.role() = 'service_role');

-- ─── Waitlist ─────────────────────────────────────────────────────────────────
-- Pre-launch email signups from the marketing landing page. Not part of the
-- mesh protocol, so dedup is by the normalized email itself (a natural unique
-- key), not a content hash like channel_records/location_points.

create table if not exists public.waitlist_entries (
  email      text        primary key,
  country    text        not null default '',
  created_at timestamptz not null default now()
);

alter table public.waitlist_entries enable row level security;
