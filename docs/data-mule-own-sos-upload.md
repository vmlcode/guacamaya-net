# Data‑mule: subida del SOS **propio** al backend

> Estado: implementado en este PR (lado Android). Complementa el flujo
> `POST /ingest` descrito en `backend_final.md` y `backend/CLAUDE.md`.

## Problema

El mapa del dashboard (`GET /dashboard` → lee `solicito-ayuda`) se alimenta de los
`LocationPoint` que el backend deriva de cada frame verificado en `POST /ingest`.
La app sube frames con `IngestUploadWorker` (gated por conectividad).

Pero el uploader solo subía **frames recibidos de otros dispositivos**. La tabla
Room `messages` se llenaba en un único sitio — `FloodRouter.insert` (ruta de
**recepción** BLE). El **SOS propio**, construido en
`GuacamayaForegroundService.startBroadcast` → `signedPayload`, se emitía por BLE
pero **nunca se persistía** en `messages`, así que `selectUploadable` jamás lo
devolvía y el mule nunca lo subía.

**Síntoma:** con un solo teléfono (mando SOS, tengo internet) el mapa quedaba
vacío. El SOS propio solo llegaba al backend si **otro** teléfono lo oía por BLE
y ese otro hacía de mule.

## Flujo objetivo

- **Con internet:** el dispositivo sube su propio SOS al backend (`POST /ingest`)
  **y** lo sigue difundiendo por la mesh.
- **Sin internet:** lo difunde por la mesh; otro dispositivo con conexión lo
  retransmite y lo sube. *(Esto ya funcionaba.)*

## Implementación

Se reutiliza el mule existente (conectividad + retry/backoff de WorkManager): el
frame propio se persiste en `messages` como cualquier frame subible, pero marcado
con una bandera `own` para que **no** contamine el radar ni el contador de
"dispositivos recibidos".

### 1. Esquema — bandera `own` (`mesh/MessageStore.kt`)

- Nueva columna `own` (`MessageEntity.own: Boolean = false`).
- **Migración Room v3 → v4** (`MIGRATION_3_4`, no destructiva):
  `ALTER TABLE messages ADD COLUMN own INTEGER NOT NULL DEFAULT 0`.
- Consultas de UI excluyen los propios (`WHERE own = 0`): `observeRecent`,
  `observeCount`, `observeNodeCount`, `observeLatestPerNode`,
  `latestHelpFramesPerNode`. Así el dispositivo no se cuenta a sí mismo ni se
  re‑difunde su propio frame como relay.
- `selectUploadable` / `countUploadable` **no** filtran `own` → el mule sí lo sube.

### 2. Política de cadencia (`ingest/OwnUploadPolicy.kt`, pura/JVM‑testable)

Persistir el frame propio solo cuando aporta un punto nuevo al mapa:

- **Primer frame de la sesión** → siempre (una víctima quieta igual obtiene 1 pin).
- Después, solo si el fix se movió **≥ 50 m** (haversine) respecto al último
  punto subido → traza la trayectoria real sin inundar de pines duplicados.

El estado (`lastOwnUploadLatE7/LonE7`) se reinicia al iniciar cada sesión de
broadcast (`startBroadcast`).

### 3. Persistencia + disparo (`service/GuacamayaForegroundService.kt`)

`persistOwnFrameForUpload(dao, id, payload22, sig64)`, llamado tras emitir el
frame propio (bootstrap y cada turno propio del rotador):

1. `Payload.decode` del frame propio.
2. Skip si **no es help‑request** (los beacons de presencia no son SOS).
3. Skip si **sin fix GPS** (`lat == 0 && lon == 0`) — no hay nada que mapear.
4. Skip si `OwnUploadPolicy.shouldUpload` dice que no se movió lo suficiente.
5. Inserta `MessageEntity(own = true, uploaded = false, rssi = 0, …)` y, si fue
   nuevo, encola `IngestUploadWorker` (que espera conectividad).

## Por qué no rompe privacidad

Las posiciones subidas pasan por las **mismas** vías de lectura ya endurecidas:
el read público (`GET /channels/:id/records`) y el broadcast WS
(`broadcastRecord`) las degradan con `sanitizeRecordForPublic` (~1 km, sin
`frameB64`). La posición exacta sigue solo en `GET /locations` / WS `"locations"`
(read key). Subir el SOS propio **no** amplía la exposición.

## Dedup

El backend deduplica por `id = SHA‑256(payload)`. Si este teléfono sube su frame
y además otro mule sube el mismo frame relayado, colapsan en **un** registro /
punto. Sin pines dobles.

## Archivos

| Archivo | Cambio |
|---|---|
| `mesh/MessageStore.kt` | columna `own`, exclusión en consultas de UI, `MIGRATION_3_4`, versión DB → 4 |
| `service/GuacamayaForegroundService.kt` | `persistOwnFrameForUpload`, reset de sesión, call sites |
| `ingest/OwnUploadPolicy.kt` | nueva política de cadencia por movimiento (pura) |
| `ingest/OwnUploadPolicyTest.kt` | tests JVM de la política |

## Pruebas

- **Unit (JVM):** `./gradlew :app:testDebugUnitTest` — incluye `OwnUploadPolicyTest`.
- **Build:** `./gradlew :app:assembleDebug`.
- **Manual:** un solo teléfono con internet → iniciar SOS → el pin aparece en
  `GET /dashboard` (a ~1 km). Mover el teléfono > 50 m → aparece un punto nuevo
  (trayectoria). Sin permiso/fix GPS → no se sube (log
  `broadcasting without coordinates`).

> Nota: el build/test de Android no se ejecutó en el entorno donde se redactó este
> cambio (sin Android SDK); correr los comandos de arriba antes de mergear.
