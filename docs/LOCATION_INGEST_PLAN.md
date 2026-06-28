# 🦜 Guacamaya Red — Plan: Ingesta de Ubicaciones (historial / moving map)

> ⚠️ **SUPERSEDED (parcialmente) tras el merge de `develop`.** El modelo de
> ingesta cambió a **zero-trust data-mule de tramas firmadas**: las ubicaciones
> ya **no** entran por un endpoint de JSON confiable. El `POST /ingest/locations`
> fue **eliminado**; ahora cada `LocationPoint` se **deriva de una trama mesh
> verificada por Ed25519** (`mesh/frame.ts` → `decodeAndVerifyFrame`) dentro de
> `POST /ingest`, con `deviceId` = pubkey de origen (no la mula). Lo que sigue
> vigente de este plan: el **modelo append-only**, el **dedupe por `id`**
> (`getLocationId`), la capa `store.ts`/`locationsRepo.ts`, la tabla
> `location_points`, `GET /locations` (solo lectura) y las notas de privacidad/
> retención (§6). Ignora las secciones que describen validar/confiar JSON del
> cliente (§4 `POST /ingest/locations`).
>
> Branch: `BR-01-Add-location-on-backend`
> Objetivo: nuevo endpoint para **guardar TODAS las ubicaciones** que reportan
> los devices (historial / serie temporal), subidas en lote ("data mule") por
> el primer device que recupera internet, para reconstruir el **movimiento de
> las personas en un mapa animado** (trayectorias en el tiempo).

---

## 1. Flujo (lo que pidió el usuario)

```
Devices intercambian ubicaciones por mesh
(lat, lon, deviceId, timestamp)            ── mesh ──►  ...
                                                          │
El PRIMER device que vuelve a la última milla            │
y recupera internet sube TODO el lote ───────────────────┘
        │
        ▼
   POST /ingest/locations   (batch de N puntos)
        │
        ▼
   BACKEND: append-only, dedupe por (deviceId, timestamp)  ──►  location_points (Supabase)
        │
        ▼
   GET /locations   ◄── dashboards (moving map: trayectoria por device en el tiempo)
```

**Modelo = append-only (igual que `ChannelRecord`).** Guardamos **cada punto**,
no solo el último. Es una **serie temporal**: muchas filas por device, una por
fix GPS. Esto permite animar el movimiento (trayectoria) en el dashboard.

### ⚠️ El problema de los duplicados entre mulas (lo que pidió el usuario)

El mismo punto llega al backend **varias veces**, porque:

```
Punto P del device-A  ──mesh──►  device-B y device-C lo reciben
                                        │            │
        device-B vuelve a la última milla y sube su lote  →  llega P
        device-C vuelve MÁS TARDE y sube SU lote          →  llega P otra vez
```

Cada mula sube **todo lo que recogió**, y por el gossip mesh los lotes **se
solapan**. Sin dedupe, P se guardaría 2, 3, N veces.

**Solución (consistente con todo el sistema): dedupe por `id` = hash del
contenido**, exactamente como `ChannelRecord` (`getRecordId` en
`packages/shared/src/crypto.ts`) y el merge de gossip (`mergeLogs`: *unión +
dedupe por id*). El `id` se calcula del **contenido inmutable del punto**:

```
id = SHA-256( deviceId : lat : lon : timestamp : accuracy )
```

- `deviceId` = **device de ORIGEN** (la persona ubicada), **NO** la mula que
  sube el lote. Inmutable mientras P viaja por el mesh.
- `timestamp` = momento del fix original, también inmutable.

→ El mismo punto, suba quien lo suba y las veces que sea, produce **el mismo
`id`** y el backend lo ignora (`upsert ignoreDuplicates`). Idempotente por
diseño. (Una PK compuesta `(deviceId, timestamp)` también funcionaría, pero el
`id` por contenido es lo que ya usa el sistema y además detecta corrupción del
lat/lon, no solo colisiones de tiempo.)

---

## 2. Modelo de datos

Nuevo tipo en `packages/shared/src/types.ts`:

```ts
export interface LocationPoint {
  id: string;         // SHA-256 del contenido canónico → clave de dedupe
  deviceId: string;   // id pseudónimo del device de ORIGEN (no la mula; no PII)
  lat: number;        // latitud  [-90, 90]
  lon: number;        // longitud [-180, 180]
  timestamp: number;  // unix ms del fix GPS (momento de la ubicación)
  accuracy?: number;  // precisión en metros (opcional)
}
```

Helper de `id` en `packages/shared/src/crypto.ts`, espejo de `getRecordId`:

```ts
export function getLocationId(p: Omit<LocationPoint, "id">): string {
  const content = `${p.deviceId}:${p.lat}:${p.lon}:${p.timestamp}:${p.accuracy ?? ""}`;
  return bytesToHex(sha256(new TextEncoder().encode(content)));
}
```

El device calcula el `id` al crear el punto (o lo calcula el backend al ingerir,
de forma determinista — mismo contenido ⇒ mismo `id`). Exportado vía
`packages/shared/src/index.ts` (ya hace `export * from "./types.js"` / `crypto.js`).

**Privacidad:** `deviceId` seudónimo (mismo esquema que `author: "device-<pubkey>"`).
⚠️ Aquí sí guardamos **historial de trayectoria**, que es **más sensible** que la
última ubicación: permite reconstruir movimientos de una persona. Mitigaciones en §6
(retención por TTL, seudonimato, acceso restringido al historial).

---

## 3. Capa de almacenamiento (espejo del patrón existente)

Se replica el patrón `store.ts` (in-memory) + `*Repo.ts` (Supabase + fallback),
igual que `channelsStore` / `channelsRepo`. Al ser append-only, el contrato se
parece mucho al de canales.

### 3.1 `backend/src/locations/store.ts` (fallback in-memory)

```ts
import { LocationPoint } from "@guacamaya/shared";

// Append-only. Clave de dedupe: el `id` (hash de contenido). Idéntico a channelsStore.
const points = new Map<string, LocationPoint>();

export const locationsStore = {
  // @returns true si era nuevo, false si duplicado (mismo id ⇒ misma mula u otra, da igual).
  add(p: LocationPoint): boolean {
    if (points.has(p.id)) return false;
    points.set(p.id, p);
    return true;
  },

  // Todos los puntos desde `since`, orden cronológico (para reproducir el mapa).
  getPoints(since = 0, deviceId?: string): LocationPoint[] {
    return Array.from(points.values())
      .filter((p) => p.timestamp > since && (!deviceId || p.deviceId === deviceId))
      .sort((a, b) => a.timestamp - b.timestamp);
  },

  clear() { points.clear(); },
};
```

### 3.2 `backend/src/db/locationsRepo.ts` (Supabase + fallback)

Mismo contrato; persiste a Supabase cuando está configurado. El append-only con
dedupe se expresa con `upsert(..., { onConflict: "id", ignoreDuplicates: true })`
— **exactamente el mismo patrón que `channelsRepo.addRecord`** (que también usa
`onConflict: "id"`).

```ts
// add(point) → upsert ignoreDuplicates sobre `id`; devuelve si insertó (false = re-subida).
// getPoints(since, deviceId?) → select .gt("timestamp", since)[.eq("device_id", …)]
//                               .order("timestamp", { ascending: true })
```

Para lotes grandes: aceptar `upsert` de un array completo en una sola llamada
(más eficiente que punto a punto).

### 3.3 Esquema SQL — `backend/supabase/schema.sql`

```sql
create table if not exists public.location_points (
  id         text primary key,             -- SHA-256 del contenido → dedupe idempotente
  device_id  text             not null,    -- id pseudónimo del device de origen
  lat        double precision not null,
  lon        double precision not null,
  "timestamp" bigint          not null,    -- unix ms del fix
  accuracy   double precision,
  created_at timestamptz not null default now()
);

-- Reproducir trayectoria de un device en orden temporal.
create index if not exists location_points_device_ts_idx
  on public.location_points (device_id, "timestamp");

-- Ventana temporal global para el moving map ("todos desde X").
create index if not exists location_points_ts_idx
  on public.location_points ("timestamp");

alter table public.location_points enable row level security;  -- service-role la salta
```

---

## 4. Endpoints (`backend/src/locations/routes.ts`)

Nuevo plugin de rutas, registrado en `index.ts` junto a `channelRoutes`.

### `POST /ingest/locations` — subida en lote (data mule)

```jsonc
// body
{ "locations": [
  { "deviceId": "device-ab12", "lat": 10.49, "lon": -66.87, "timestamp": 1719500000000, "accuracy": 12 },
  { "deviceId": "device-ab12", "lat": 10.50, "lon": -66.86, "timestamp": 1719500030000 }
]}
```

Lógica:
1. Validar que `locations` es array (400 si no).
2. Por cada item validar tipos y rangos (`lat`/`lon` finitos y en rango,
   `timestamp` número, `deviceId` string no vacío). Descartar inválidos sin
   abortar el lote.
3. **Recalcular el `id`** en el backend con `getLocationId(p)` (no confiar en el
   `id` que mande el cliente) → garantiza que un punto idéntico de dos mulas
   distintas colisione en el mismo `id`. `locationsRepo.add(...)` (idealmente
   upsert del array completo) → contar cuántos eran **nuevos** (los duplicados
   de re-subida devuelven `false` y no se recuentan).
4. (Opcional) `broadcastLocation(p)` por WS para el mapa en vivo.
5. Responder `{ success: true, ingested: <nuevos>, received: <total> }`.

> Reusar el rate-limit global ya registrado en `index.ts` (anti-spam/Sybil).

### `GET /locations?since=<ms>&deviceId=<id>` — para el moving map

Devuelve **todos los puntos** desde `since`, en orden cronológico, opcionalmente
filtrados por `deviceId`. El dashboard los agrupa por `deviceId` y reproduce la
trayectoria en el tiempo. Sin auth en esta fase (ver §6).

> Futuro: paginación / límite por defecto, ya que el historial crece sin tope.

---

## 5. Integración WebSocket (opcional, fase 2)

Mapa en vivo: añadir `broadcastLocation(p)` en `ws/server.ts` que emite
`{ type: "location", data: p }` a clientes suscritos a un canal lógico
`"locations"`. No bloquea la fase 1.

---

## 6. Seguridad / privacidad

- ⚠️ **Historial de trayectoria = dato sensible.** Reconstruye los movimientos
  de una persona. Más delicado que "última ubicación".
- **Retención:** definir TTL (p. ej. borrar puntos con `created_at` > N días)
  vía job/cron en Supabase. No retener indefinidamente.
- **Seudonimato:** `deviceId` derivado de la llave self-signed del device.
- **Anti-abuso:** rate-limit global activo. Descartar fixes con `timestamp`
  futuro (> `now + margen`).
- **Lectura:** `GET /locations` debería ir detrás de auth/API-key dado que
  expone trayectorias; mantenerlo abierto solo para la demo interna.

---

## 7. Checklist de implementación

- [ ] `LocationPoint` (con `id`) en `packages/shared/src/types.ts`
- [ ] `getLocationId()` en `packages/shared/src/crypto.ts` (espejo de `getRecordId`)
- [ ] `backend/src/locations/store.ts` (append-only, dedupe por `id`)
- [ ] `backend/src/db/locationsRepo.ts` (Supabase upsert `onConflict: "id"` + fallback)
- [ ] Tabla `location_points` (PK = `id` + índices) en `supabase/schema.sql`
- [ ] `backend/src/locations/routes.ts`: `POST /ingest/locations` + `GET /locations`
      (recalcula `id` en el server)
- [ ] Registrar `locationRoutes` en `backend/src/index.ts`
- [ ] (Opcional) `broadcastLocation` en `ws/server.ts`
- [ ] Tests: **re-subida de la misma mula y de mulas distintas dedupea por `id`**,
      validación de rangos, orden temporal
- [ ] Nota de privacidad + retención en `backend/README.md`

---

## 8. Decisiones abiertas

1. **¿Endpoint dedicado `/ingest/locations` o extender `/ingest`?**
   → Dedicado. Tabla y validación propias; más limpio.
2. **Dedupe por `id` (hash de contenido)** — resuelve la re-subida de la misma
   info por mulas distintas (§1). Único riesgo: si dos fixes genuinamente
   distintos tuvieran exactamente el mismo `deviceId:lat:lon:timestamp:accuracy`
   serían el mismo punto (correcto). No hay falsos duplicados.
3. **Retención del historial** (§6): ¿cuántos días? Definir antes de producción.
4. **`GET /locations`**: límite/paginación por defecto para no devolver todo el
   historial de una vez.

---

*Plan de ingesta de ubicaciones (historial / moving map) — Guacamaya Red.
Alineado con el patrón append-only `channelsStore`/`channelsRepo` existente.*
