# Backend Data-Mule

Rama `develop`. Monorepo Bun + TypeScript. Su rol en el diseño actual es acotado: ser el **punto de
ingesta "data-mule"** de la malla [[Guacamaya (Android)]]. Un teléfono que recupera conectividad sube
los frames firmados que recogió; el backend los **re-verifica** y persiste.

Parte de [[GuacamayaProject]]. El formato de los frames está en [[Protocolo y Frame]].

> La app Expo que también vivía en `app/` está **descartada** — la app Kotlin la reemplaza. Ignorar
> `app/`. Ver el porqué en [[Arquitectura y Decisiones]].

## Stack y layout

- `packages/shared/` — tipo `ChannelRecord`, `getRecordId` (SHA-256 de contenido canónico),
  `verifyRecordSignature`, `mergeLogs` (unión + dedupe por id + verificación). Puro, con tests `bun:test`.
- `backend/src/index.ts` — bootstrap Fastify (CORS, rate-limit 100/min), rutas, WS, shutdown limpio.
- `backend/src/channels/routes.ts` — API HTTP.
- `backend/src/crypto/` — `keys.ts` (identidad del servidor), `signer.ts` (firma registros oficiales).
- `backend/src/mesh/frame.ts` — **decodifica y verifica zero-trust los frames binarios** de la malla.
- `backend/src/db/` — `channelsRepo.ts` (Supabase con fallback en memoria), `supabase.ts`. Esquema en `backend/supabase/schema.sql`.
- `backend/src/ws/server.ts` — WebSocket `/ws`, subscribe/unsubscribe por canal, `broadcastRecord`.

## Modelo de datos: `ChannelRecord`

`{ id, channel, timestamp, ttl, author, verified, payload, sig? }`. El `id` es un SHA-256
determinista del contenido canónico → el almacenamiento deduplica por `id` (la misma unión+dedupe
que el log de gossip de la malla). `verified:true` = firmado oficial por el backend; los reportes de
comunidad son siempre `verified:false`.

## API HTTP

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/pubkey` | Llave pública del backend (para verificar registros oficiales). |
| GET | `/channels` | Lista de canales. |
| GET | `/channels/:id/records?since=<ms>` | Registros desde un timestamp. |
| POST | `/channels/:id/records` | Crea registro **oficial**: el backend lo firma, persiste y emite por WS. |
| POST | `/ingest` | **Subida data-mule de la malla** (abajo). |

## `POST /ingest` — el puente data-mule (flujo clave)

Body: `{ "frames": ["<base64>", ...] }`. Cada frame es el service-data BLE **sin el byte de TTL** →
118 B = `22 payload + 32 pubkey + 64 firma` (se tolera el frame completo de 119 B quitándole el primero).

**Zero-trust: cada frame se re-verifica antes de persistir nada** — el backend nunca confía en el
cliente. `decodeAndVerifyFrame` (`backend/src/mesh/frame.ts`) replica la cascada de [[Protocolo y
Frame]]: CRC16-CCITT (espejo byte-a-byte de `Crc16.ccitt`) → ata pubkey (`SHA-256(pubkey)[0..4] ==
node_id`) → verify Ed25519 sobre los 22 B (espejo de `Signer.verify`, con `@noble/ed25519`). **No** se
aplica el skew de tiempo (un mule sube reportes viejos a propósito). Los frames válidos se guardan
como reportes de comunidad `verified:false` en el canal `solicito-ayuda`, con `id = SHA-256(payload)`
para dedupe. La respuesta reporta `{ ingested, duplicate, rejected, reasons }`.

### Verificación hecha (smoke real contra el server)

| Caso | Resultado |
|---|---|
| Frame válido firmado | `ingested: 1` ✅ |
| Byte de firma alterado | `rejected: signature invalid` ✅ |
| Payload alterado con CRC recalculado | `rejected: signature invalid` ✅ (prueba que la firma cubre el payload, no solo el CRC) |
| Mismo frame dos veces | `duplicate` ✅ |
| Basura / no-string | `rejected` con razones ✅ |

## Identidad del servidor

`crypto/keys.ts` lee `BACKEND_PRIVATE_KEY_HEX` (con alias legacy `BACKEND_PRIVATE_KEY`). Si no está,
genera un par **efímero** (advierte al arrancar) y la pubkey cambia en cada reinicio. Para identidad
estable, fijarla en `backend/.env`. Corre sin base de datos: si Supabase no está configurado, usa el
store en memoria.

Cómo correrlo: [[Build y Entorno]]. Pendientes (uploader Kotlin, SMS, etc.): [[Estado y Pendientes]].
