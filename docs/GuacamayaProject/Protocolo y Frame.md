# Protocolo y Frame

El contrato binario entre todas las capas (y entre [[Guacamaya (Android)]] y el [[Backend Data-Mule]]).
Todo dato es un **payload firmado de 22 bytes** que viaja dentro de un único frame de broadcast.

## ⚠️ Riesgo crítico: el layout binario se mantiene en sync a mano en TRES lugares

El mismo layout de 22 B / frame de 119 B está hardcodeado por separado en tres sitios. **No hay una
fuente única de verdad generada**: si cambia un offset, tamaño o el CRC en uno solo, los otros
**rechazan frames válidos en silencio** (la firma deja de verificar o el CRC falla). Mantener los tres
sincronizados a mano en cada cambio de wire-format:

1. **`android/app/.../net/guacamaya/proto/Payload.kt`** (+ `Crc16.kt`, `ble/BleConfig.kt`) — la malla.
2. **`backend/src/mesh/frame.ts`** — el decodificador zero-trust de `POST /ingest`.
3. **`packages/shared/src/mesh/constants.ts`** (+ `crc16.ts`) — las constantes compartidas del backend.

> Antes el riesgo era de 2 vías (Kotlin ↔ `frame.ts`); con el monorepo y `packages/shared/src/mesh/`
> ahora son **3**. Cualquier PR que toque offsets/tamaños/CRC debe actualizar los tres y correr los
> tests de ambos lados (`./gradlew :app:testDebugUnitTest` y `bun test`).

## El frame de 119 bytes (BLE service data)

```
byte 0        TTL de salto (uint8, MUTABLE, NO firmado)
bytes 1..22   payload firmado (22 B)
bytes 23..54  llave pública Ed25519 (32 B)
bytes 55..118 firma Ed25519 (64 B)
```

`SERVICE_DATA_SIZE = 1 + 22 + 32 + 64 = 119` (`ble/BleConfig.kt`).

> El **TTL de salto vive FUERA del payload firmado** a propósito: cada relay lo decrementa. Si
> estuviera dentro de los 22 B firmados, la firma del origen se rompería en el siguiente salto
> (re-firmar es imposible: el `node_id` está atado a la llave del origen). El payload lleva además un
> `hopTtl` informativo en su nibble de flags = valor *inicial* del origen; el presupuesto de saltos
> **vivo** es el byte 0. Decisión detallada en [[Arquitectura y Decisiones]].
>
> Para subir al [[Backend Data-Mule]], el cliente **quita el byte de TTL** → frame de 118 B
> (`22 payload + 32 pubkey + 64 firma`; el backend tolera el frame de 119 B quitándole el primero).

## Payload de 22 bytes (`proto/Payload.kt`, big-endian)

| Offset | Tam | Campo | Notas |
|---|---|---|---|
| 0 | 4 | `latE7` | int32, latitud × 1e7 |
| 4 | 4 | `lonE7` | int32, longitud × 1e7 |
| 8 | 4 | `tsUnix` | uint32, segundos |
| 12 | 4 | `nodeId` | primeros 4 B de `SHA-256(pubkey)` |
| 16 | 1 | `flags` | bitfield (abajo) |
| 17 | 1 | `sosType` | enum (abajo) |
| 18 | 2 | `msgId` | uint16, secuencia por nodo (dedupe) |
| 20 | 2 | `crc16` | CRC16-CCITT (poly 0x1021, init 0xFFFF) sobre bytes 0..19 |

**flags (byte 16):** bit0 `hasHeavy`, bit1 `critical`, bits2-3 `batteryBucket` (0-3), bits4-7 `hopTtl` (0-15).

**sosType (byte 17):** 0 medical · 1 distress · 2 food · 3 water · 4 shelter · 5 fire · 6 violence · 7 other.

## Firma

- Ed25519 (RFC 8032) sobre los **22 bytes completos** del payload (CRC incluido).
- La llave pública viaja en cada frame → cualquier receptor verifica sin registro ni servidor.
- El `node_id` ata el payload a la pubkey: si `SHA-256(pubkey)[0..4] != node_id`, se descarta.

## La cascada de rechazo (`mesh/FloodRouter.kt`)

El control de flujo más importante. Los frames entrantes pasan un embudo ordenado, **lo más barato
primero**, antes de almacenarse o retransmitirse:

1. `SHA-256(pubkey)[0..4] == payload.nodeId` (ata pubkey; frena el swap de llave)
2. `Payload.decode` ok (CRC16 — rechazo barato antes del verify caro)
3. `|tsUnix − now| ≤ 300 s` (`MAX_TS_SKEW_SECONDS`, ventana de replay)
4. Verificación Ed25519 (`Signer.verify`, el paso caro, al final)

Si pasa → `DedupeCache.admit` → persistir en Room (pruning batcheado, conserva 25 000 filas; ver
[[Guacamaya (Android)]]) → si es fresco, **retransmitir los bytes payload/pubkey/sig sin cambios** con
el TTL de salto decrementado; deja de retransmitir cuando llegaría a 0 (el frame igual se guarda para
que el usuario local lo vea). El loop-back dentro de la ventana lo suprime el dedupe, indexado por
`(nodeId, msgId)`, LRU + TTL 5 min.

> **`FloodRouter` recibe `sig64` pero `MessageEntity` NO la persiste** (no hay columna `sig`). Para el
> `IngestClient` pendiente habrá que añadirla, porque el frame de subida a `/ingest` necesita los 64 B
> de firma. Ver [[Estado y Pendientes]].

> En el [[Backend Data-Mule]] la cascada se replica **idéntica salvo el paso 3** (skew de tiempo): un
> data-mule sube reportes viejos a propósito, así que ahí no se aplica la ventana de replay.
