# Resolve y Confirmación de Rescate

Flujo del [[Backend Data-Mule]] para dar por **resuelta** una señal SOS activa **sin requerir que el
dispositivo de la víctima esté vivo**. Un **buscador** (rescatista, voluntario, vecino) que llega al
sitio co-firma, junto con otros testigos, un sobre que desarma el SOS. El **quórum M-de-N reemplaza la
confirmación de la víctima** — cubre escenarios de demolición / aplastamiento / batería agotada.

Parte de [[GuacamayaProject]]. Decisión de diseño en [[Arquitectura y Decisiones]] §8. Seguridad/rate
limits en [[Seguridad Backend]].

> **Estado: backend implementado y testeado; clientes pendientes.** Los endpoints `POST /resolve` y
> `POST /resolve/evidence` y el canal `resuelto` existen. La app del buscador, la consola del
> coordinador y la integración con la app mesh Android son **trabajo futuro** (diseño en el repo:
> `docs/future-rescue-suite.md`).

## Roles

| Rol | Dispositivo | Responsabilidad |
|---|---|---|
| **Víctima / originador** | App mesh [[Guacamaya (Android)]] | Emite el SOS firmado por BLE. **No requiere estar vivo** para que se resuelva. |
| **Buscador / testigo** | App móvil del buscador (futuro) | Llega al sitio, captura evidencia, co-firma. Mínimo `RESOLVE_QUORUM_REQUIRED` (default 2) testigos distintos. |
| **Coordinador** | Consola web (futuro) | Visualiza SOS pendientes, disputas, reputación. Promueve/reabre manual. |
| **Backend** | `backend/src/resolve/` | Verifica firmas, aplica anti-troll, persiste recibos, emite `ChannelRecord` al canal `resuelto`. **Implementado.** |

## Cripto del sobre (`packages/shared/src/resolve.ts`)

Formato canónico byte-estable, versión **`guacamaya.resolve.v1`**. Cada testigo firma con su **propio**
keypair Ed25519 (mismo patrón Keystore que `net.guacamaya.crypto.Identity`; `deviceId = "device-" +
hex(pubkey)`):

```
canonicalResolveBytes(envelope) =
  "guacamaya.resolve.v1\n" + targetSosId + "\n" + targetSosAuthor + "\n" + submittedAt + "\n" + (note ?? "") + "\n"

witnessMessageBytes(envelope, w) =
  canonicalResolveBytes(envelope) +
  w.deviceId + "\n" + w.lat.toFixed(7) + "\n" + w.lon.toFixed(7) + "\n" + w.ts + "\n" + w.imageHash + "\n" +
  (w.macObservationHashes?.join(",") ?? "") + "\n"
```

Cada testigo firma `sha256(witnessMessageBytes)`. `getResolveId` = `sha256(canonicalBytes ||
deviceIds_ordenados)` → id idempotente (mismo target + mismo set de testigos ⇒ mismo recibo). Es un
**tercer esquema de cripto** distinto al del frame de la malla y al de los registros oficiales
(ver [[Arquitectura y Decisiones]] §10).

## Modelo de datos (tipos en `packages/shared/src/types.ts`)

- **`ResolveWitness`** — `{ deviceId, pubkey, lat, lon, ts, imageHash, uploadToken?, macObservationHashes?, sig }`.
- **`ResolveEnvelope`** — `{ targetSosId, targetSosAuthor, witnesses[1..N], submittedAt, note? }`.
- **`ResolveReceipt`** — `{ id, targetSosId, targetSosAuthor, status, quorumNeeded, quorumSeen, witnessDeviceIds[], createdAt, cooldownEndsAt?, disputedReason? }`.
- **`ResolveStatus`** — `pending | cleared | disputed | rejected`.

Esquema persistido en `backend/supabase/schema.sql`: `resolve_receipts` (un recibo por target) +
`resolve_witnesses` (un row por `(target_sos_id, device_id)`).

## Endpoints (`backend/src/resolve/routes.ts`)

- **`POST /resolve/evidence`** — sube una imagen (octet-stream) → `{ imageHash, storageKey,
  uploadToken, expiresInMs }`. El `uploadToken` es un **HMAC** que ata la imagen a una ventana corta
  (`RESOLVE_EVIDENCE_TTL_MS`, default 5 min). En prod requiere read key.
- **`POST /resolve`** — envía el `ResolveEnvelope`. El backend re-verifica **cada** firma y corre la
  cascada anti-troll. Respuesta: `{ accepted, status, targetSosId, quorumNeeded, quorumSeen,
  receiptId?, cooldownEndsAt?, reason(s) }`.
- **`GET /channels/resuelto/records?since=<ms>`** — histórico de transiciones (canal oficial).

## Cascada anti-troll (lo barato primero, `routes.ts`)

1. Forma del sobre válida (`isValidResolveEnvelope`) → 400.
2. **Target existe** (`channelsRepo.getById`) → `target_unknown` (404).
3. **Recencia** del target ≤ `RESOLVE_TARGET_MAX_AGE_H` (default 72 h) → `target_stale` (410).
4. `targetSosAuthor` del sobre coincide con el autor real del record → si no, `target_author_mismatch`.
5. **Rate-limit por `deviceId`** del submitter: 5/hora (leaky-bucket).
6. Por testigo (se saltan en silencio, no bloquean a los demás):
   - **Testigo ≠ originador** (`deviceId != target.author`).
   - **Un testigo por target** (dup `(target, device)` se salta).
   - **Geo gate**: haversine ≤ `RESOLVE_GEO_RADIUS_KM` (default 5 km) del SOS original.
   - **Firma Ed25519** del testigo válida.
   - **uploadToken** HMAC válido para su `imageHash` (si lo trae).
7. Se aceptan solo los testigos nuevos válidos. Si ninguno nuevo, `accepted:false` (`duplicate_witness`
   / razón del primer rechazo).

## Estados y cooldown

- Al persistir testigos nuevos se compone el recibo acumulativo. Mientras acumula o está en cooldown,
  `status = pending`.
- Al **cruzar el quórum** por primera vez se arranca un **cooldown** (`RESOLVE_COOLDOWN_MIN`, default
  15 min): `cooldownEndsAt = now + cooldown`, se emite `broadcastResolve` y un `ChannelRecord` firmado
  al canal `resuelto` con `payload.event = "pending"`.
- **Veto del originador**: si el origen re-dispara el mismo SOS durante el cooldown, el recibo se
  marca `disputed` automáticamente.
- El SOS original **no se borra** — el canal `solicito-ayuda` es append-only. Para "ocultar" un SOS
  resuelto en la UI, los clientes correlacionan `resuelto` ↔ `solicito-ayuda` por `targetSosId`.

## Eventos WebSocket

| Canal | Evento | Carga |
|---|---|---|
| `resolves` | `resolve` | `ResolveReceipt` completo (cualquier transición). |
| `resuelto` | `record` | `ChannelRecord` firmado por backend, `payload.event ∈ {pending, cleared, disputed}`. |
| `solicito-ayuda` | `record` | sin cambios — el SOS original sigue visible. |

> Privacidad: `witnessDeviceIds` va en el `ResolveReceipt` (para coordinadores) pero **no** en el
> `ChannelRecord` de `resuelto` (que solo lleva `witnessCount` agregado).

## Pendiente / futuro (no implementado)

- **Clientes**: app móvil del buscador (captura + co-firma + descubrimiento de testigos por BLE/Wi-Fi
  Aware) y consola del coordinador (mapa, cola de disputas, reputación). Sin esto el flujo no se
  ejercita en campo.
- `GET /resolve/:targetSosId` (estado actual + testigos), `GET /resolve/evidence/:hash` (admin),
  `POST /resolve/:receiptId/promote|dispute` (coordinador).
- **Reputación persistente** de testigos (`witness_reputation`), **prueba de presencia BLE firmada**
  (hoy `macObservationHashes` es solo forense), quórum dinámico para `critical`, geo-correlación entre
  testigos.
- **Multi-proceso**: el mutex actual es in-process; para escalar, advisory locks de Postgres.

Config de límites/keys en [[Seguridad Backend]]. Diseño completo de la suite de rescate en el repo:
`docs/future-rescue-suite.md`.
