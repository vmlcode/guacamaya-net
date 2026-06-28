# Backend Data-Mule

Monorepo Bun + TypeScript, mitad `backend/` + `packages/` de la rama `develop`. Su rol original es el
**punto de ingesta "data-mule"** de la malla [[Guacamaya (Android)]]: un teléfono que recupera
conectividad sube los frames firmados que recogió; el backend los **re-verifica** y persiste. Hoy hace
además: canales oficiales, histórico de ubicación para el mapa móvil, y el flujo de
[[Resolve y Confirmacion de Rescate]].

Parte de [[GuacamayaProject]]. El formato de los frames está en [[Protocolo y Frame]]. El
endurecimiento (API keys, CORS, rate limits) está en [[Seguridad Backend]].

> La app Expo que vivía en `app/` está **descartada** — la app Kotlin la reemplaza. Ver el porqué en
> [[Arquitectura y Decisiones]].

## Stack y layout

- `packages/shared/` — el **contrato compartido**, framework-agnóstico, con tests `bun:test`:
  - `types.ts` — `ChannelRecord`, `LocationPoint`, y los tipos de Resolve (`ResolveEnvelope`,
    `ResolveWitness`, `ResolveReceipt`, `ResolveStatus`).
  - `crypto.ts` — `getRecordId`, `getLocationId`, `verifyRecordSignature`.
  - `merge.ts` — `mergeLogs` (unión + dedupe por id + verificación, el log de gossip de la malla).
  - `resolve.ts` — cripto del flujo Resolve (`canonicalResolveBytes`, `witnessMessageBytes`,
    `signWitness`, `verifyResolveEnvelope`, `getResolveId`). Ver [[Resolve y Confirmacion de Rescate]].
  - `mesh/` — `constants.ts` + `crc16.ts`: **constantes de wire-format** que deben quedar
    byte-idénticas con `net.guacamaya.proto.*` (riesgo de sync de 3 vías en [[Protocolo y Frame]]).
- `backend/src/index.ts` — bootstrap Fastify (CORS, helmet, rate-limit global 100/min), rutas, WS, shutdown limpio.
- `backend/src/channels/routes.ts` — API HTTP de canales + `/ingest`. `store.ts` — fallback en memoria.
- `backend/src/locations/routes.ts` — API HTTP de ubicación (read-only). `store.ts` — fallback en memoria.
- `backend/src/resolve/` — `routes.ts`, `evidence.ts`, `store.ts`: el flujo de resolución de rescate.
- `backend/src/security/` — `config.ts`, `auth.ts`, `rateLimit.ts`, `validation.ts`, `keygen.ts`. Ver [[Seguridad Backend]].
- `backend/src/crypto/` — `keys.ts` (identidad del servidor), `signer.ts` (firma registros oficiales).
- `backend/src/mesh/frame.ts` — **decodifica y verifica zero-trust los frames binarios** de la malla.
- `backend/src/db/` — `channelsRepo.ts`, `locationsRepo.ts`, `resolvesRepo.ts` (Supabase con fallback en memoria), `supabase.ts`. Esquema en `backend/supabase/schema.sql`.
- `backend/src/ws/server.ts` — WebSocket `/ws`: subscribe/unsubscribe por canal, `broadcastRecord`, `broadcastLocation`, `broadcastResolve`.

## Modelos de datos

- **`ChannelRecord`** `{ id, channel, timestamp, ttl, author, verified, payload, sig? }`. El `id` es un
  SHA-256 determinista del contenido canónico → el almacenamiento deduplica por `id`. `verified:true`
  = firmado oficial por el backend; los reportes de comunidad son siempre `verified:false`. Canales:
  `alertas`, `refugios`, `ayuda-medica`, `estoy-bien`, `solicito-ayuda`, `resuelto`.
- **`LocationPoint`** `{ id, deviceId, lat, lon, timestamp, accuracy? }`. Trayectoria GPS para el mapa
  móvil. `id` = SHA-256 canónico (dedupe). `deviceId` = `device-<pubkey>` del **origen**, no del mule.

## API HTTP

| Método | Ruta | Qué hace | Auth |
|---|---|---|---|
| GET | `/pubkey` | Llave pública del backend (verificar registros oficiales). | — |
| GET | `/channels` | Lista de canales. | — |
| GET | `/channels/:id/records?since=<ms>` | Registros desde un timestamp. | — |
| POST | `/channels/:id/records` | Crea registro **oficial**: el backend lo firma, persiste, emite por WS. | `X-Api-Key` admin |
| POST | `/ingest` | **Subida data-mule** de frames binarios (abajo). | — (zero-trust) |
| GET | `/locations?since=<ms>&deviceId=<id>` | Puntos de ubicación para el mapa móvil (read-only). | read key |
| POST | `/resolve/evidence` | Sube imagen de evidencia → `imageHash` + `uploadToken`. | read key (en prod) |
| POST | `/resolve` | Envía el sobre de testigos co-firmado. | rate-limit por deviceId |

Detalle de auth/keys/CORS/rate-limits en [[Seguridad Backend]]; el flujo `/resolve*` en
[[Resolve y Confirmacion de Rescate]].

## `POST /ingest` — el puente data-mule (flujo clave)

Body: `{ "frames": ["<base64>", ...] }`. Cada frame es el service-data BLE **sin el byte de TTL** →
118 B = `22 payload + 32 pubkey + 64 firma` (se tolera el frame completo de 119 B quitándole el primero).
Batch tope `MAX_INGEST_BATCH` (default 200) con rate-limit por ruta (30/min).

**Zero-trust: cada frame se re-verifica antes de persistir nada.** `decodeAndVerifyFrame`
(`backend/src/mesh/frame.ts`) replica la cascada de [[Protocolo y Frame]]: CRC16-CCITT → ata pubkey
(`SHA-256(pubkey)[0..4] == node_id`) → verify Ed25519 sobre los 22 B (con `@noble/ed25519`). **No** se
aplica el skew de tiempo (un mule sube reportes viejos a propósito). Los frames válidos se guardan
como reportes de comunidad `verified:false` en el canal `solicito-ayuda`, con `id = SHA-256(payload)`.

**Ubicación derivada del frame:** el payload de 22 B ya lleva lat/lon (bytes 0..7, int32 E7) + timestamp
→ `decodeAndVerifyFrame` devuelve también un `LocationPoint` (`deviceId = device-<pubkey origen>`; `null`
si el frame no trae fix GPS). `/ingest` lo persiste vía `locationsRepo` y lo emite a los suscriptores WS
de `"locations"`. **No existe** endpoint de ingesta JSON confiado de ubicaciones — la misma compuerta
Ed25519 protege records y posiciones (decisión en [[Arquitectura y Decisiones]] §9). Respuesta:
`{ ingested, duplicate, rejected, reasons }`.

## Invariantes que importan

- **Bun only** para package management y scripts (preferencia del proyecto). Nunca npm/node.
- `/ingest` y `/resolve` **nunca** persisten algo cuya firma no re-verificaron. La verificación es la compuerta.
- **Siempre recalcular `id` en el servidor** (`getRecordId` / `getLocationId` / `getResolveId`) — nunca confiar en el id del cliente.
- `packages/shared` debe quedar framework-agnóstico (es el contrato; no meterle deps backend-only).
- El layout binario de `frame.ts` y `packages/shared/src/mesh/` debe seguir a `proto/Payload.kt`
  (riesgo de sync de 3 vías — [[Protocolo y Frame]]).

## Identidad del servidor

`crypto/keys.ts` lee `BACKEND_PRIVATE_KEY_HEX` (alias legacy `BACKEND_PRIVATE_KEY`). Si no está, genera
un par **efímero** (advierte al arrancar). Para identidad estable, fijarla en `backend/.env`. Corre sin
base de datos: si Supabase no está configurado, usa el store en memoria.

Cómo correrlo: [[Build y Entorno]]. Pendientes (uploader Kotlin, etc.): [[Estado y Pendientes]].
