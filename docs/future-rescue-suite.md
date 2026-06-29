# Suite de Rescate — Especificación futura

> **Estado:** diseño. No hay código que lo implemente todavía. Este documento
> describe la suite que consumirá el flujo `POST /resolve` una vez que la app
> Android y un cliente de coordinador (web/escritorio) lo integren.

## Propósito

Cuando un **buscador** (rescatista, voluntario, vecino) llega al sitio donde se
originó una señal SOS activa, debe poder **darla por resuelta** desde su propio
dispositivo, sin requerir que el dispositivo de la víctima esté vivo. El backend
ya soporta esto (`POST /resolve` + `/resolve/evidence`); la suite futura es el
conjunto de clientes y herramientas operativas que lo hacen utilizable en campo:

- App móvil para el buscador (flujo de captura de evidencia + firma).
- Consola web para el coordinador (mapa, disputas, reputación).
- Integración con la app mesh Android (origen del SOS).
- Agregación forense de observaciones BLE/MAC.

## Roles

| Rol | Dispositivo | Responsabilidad |
|---|---|---|
| **Víctima / originador** | App mesh Android | Emite la señal SOS firmada por BLE mesh. No se requiere que esté vivo para resolver. |
| **Buscador / testigo** | App móvil del buscador (futuro) | Llega al sitio, captura imagen de evidencia, co-firma el resolve. Mínimo `RESOLVE_QUORUM_REQUIRED` (default 2) testigos distinctos. |
| **Coordinador** | Consola web (futuro) | Visualiza SOS pendientes, disputas, reputación de testigos. Aprueba/promueve manualmente cuando hace falta. |
| **Backend** | Este repo (`backend/`) | Verifica firmas, aplica anti-troll gates, persiste recibos, emite `ChannelRecord` a canal `resuelto`. **Ya implementado.** |

## Arquitectura de clientes

```
┌──────────────────────┐    BLE mesh    ┌─────────────────────┐
│ Víctima (Android)    │ ──────────────> │ Mules / otros nodos │
│  - firma SOS (Ed25519)│                │  - retransmiten     │
└──────────────────────┘                 └─────────┬──────────┘
                                                   │ POST /ingest
                                                   ▼
                                         ┌─────────────────────┐
                                         │ Backend GuacaMalla   │
                                         │  - verify frames    │
                                         │  - resolve flow     │◄──── POST /resolve/evidence
                                         │  - WS /resuelto     │     POST /resolve  (co-firmado)
                                         └─────────┬───────────┘
                                                   │ WS broadcast
                                                   ▼
                                         ┌─────────────────────┐
                                         │ Consola coordinador │
                                         │  + buscadores       │
                                         └─────────────────────┘
```

## App móvil del buscador (pendiente)

Pantallas mínimas:

1. **Mapa de señales activas** — suscripción WS a `resolves` + lectura de
   `GET /channels/solicito-ayuda/records` para listar SOS sin resolver.
2. **Detalle de SOS** — origen (deviceId, pseudonimizado), ubicación, edad,
   testigos ya acumulados (`GET` futuro `/resolve/:targetSosId`).
3. **Captura de evidencia** — cámara nativa → POST `/resolve/evidence` →
   obtiene `imageHash` + `uploadToken` (5 min de validez).
4. **Firma del resolve** — genera sobre (`canonicalResolveBytes` +
   `witnessMessageBytes` en `packages/shared/src/resolve.ts`) usando el
   keypair Ed25519 local del buscador. Empaqueta `ResolveEnvelope` con
   mínimo `RESOLVE_QUORUM_REQUIRED` testigos co-localizados (cada uno firma
   su slot).
5. **Coordinación entre buscadores** — Bluetooth o Wi-Fi Aware para
   descubrir otros testigos cerca, intercambiar imageHash +deviceId y
   construir el sobre conjunto. (Análogo al mesh BLE del originador.)
6. **Confirmación** — al recibir `accepted:true` con `status:"pending"`,
   mostrar cooldown restante. Si el origen re-dispara durante el cooldown,
   mostrar disputa.

### Identidad del buscador

Cada dispositivo generador de testigo mantiene un keypair Ed25519 en
Android Keystore (patrón idéntico a `net.guacamaya.crypto.Identity`).
`deviceId = "device-" + hex(pubkey)`. No hay registro previo; el primer
resolve registra al testigo implícitamente.

## Consola del coordinador (pendiente)

Módulos:

- **Mapa global** — SOS activos (puntos rojos), pendientes-cooldown (ámbar),
  resueltos (verde, últimos 24 h), disputados (morado). Suscripción WS a
  `resolves`, `resuelto`, `locations`.
- **Detalle de recibo** — lista de testigos (deviceId, lat/lon, timestamp,
  imageHash), imagen de evidencia descargable (vía endpoint futuro
  `GET /resolve/evidence/:hash` con auth de admin), observaciones MAC
  agregadas.
- **Cola de disputas** — recibos `disputed`; botones para "confirmar
  resuelto" (promover a `cleared` manualmente) o "reabrir SOS" (dejarlo
  activo, marcar testigos con baja reputación).
- **Reputación de testigos** — conteo de resolves aceptados vs. disputados
  por deviceId. Umbral configurable para bloquear testigos con alta tasa
  de falsos positivos.
- **Cuota de uso** — consumo del leaky-bucket por deviceId; alertas de
  abuso.

## Anti-troll — diseño ya implementado vs. futuro

### Ya implementado (este PR)

1. **Quórum M-of-N** (default 2-of-3) — un único buscador no puede resolver.
2. **Geo gate** — haversine ≤ 5 km desde el SOS original.
3. **Recencia** — target ≤ 72 h.
4. **Un testigo por target** — `(target_sos_id, device_id)` único.
5. **Testigo ≠ originador** — deviceId no puede coincidir con el autor del SOS.
6. **Firma Ed25519** por testigo — no repudio.
7. **uploadToken HMAC** — la imagen fue subida en los últimos 5 min.
8. **Rate-limit por deviceId** — 5 / hora (leaky-bucket independiente del
   rate-limit por IP de Fastify).
9. **Cooldown + veto** — 15 min de ventana; si el originador re-dispara el
   mismo SOS, el recibo se disputa automáticamente.
10. **Víctima muerta OK** — el quórum reemplaza la confirmación de la
    víctima. Cobertura de escenarios de demolición / aplastamiento /
    batería agotada.

### Futuro (no implementado)

- **Reputación persistente** — tabla `witness_reputation(device_id, accepted,
  disputed, last_seen)`; promedio móvil; bloqueo automático sobre umbral.
- **Prueba de presencia BLE** — testigos que observaron el deviceId del
  originador por BLE en los últimos N minutos (hash de la observación,
  firmada). Hoy el campo `macObservationHashes` es solo forense; en el
  futuro podría firmarse y ponderarse.
- **Quórum dinámico** — subir M a 3-of-5 para SOS etiquetados `critical` o
  en zonas con historial de abuso.
- **Stake / slashing** — depósito en reputación que se quema si el recibo
  se disputa. Requiere identidad más fuerte que el keypair efímero.
- **Geo-correlación entre testigos** — verificar que los testigos están
  cerca entre sí, no solo del target (evita granjas de firmas remotas
  coordinadas cerca del target).

## Modelo de datos (resumen)

Implementado en `backend/supabase/schema.sql`:

- `resolve_receipts` — un recibo por target, estados `pending | cleared |
  disputed | rejected`, cooldown, witness set acumulado.
- `resolve_witnesses` — un row por `(target_sos_id, device_id)`,
  referencia al recibo, lat/lon/ts, image_hash, MAC observations (soft),
  signature.
- Canal `resuelto` — `channel_records` con `verified:true` y
  `payload.source = "resolve-flow"`; emitido en cada transición de estado.

Pendiente (no agregado al schema):

- `witness_reputation` — agregación por deviceId.
- `resolve_disputes` — log de auditoría cuando un coordinador promueve /
  reabre manualmente.
- `resolve_evidence_audit` — re-hash periódico de imágenes almacenadas
  para detectar tampering.

## Eventos WebSocket

| Canal | Evento | Carga |
|---|---|---|
| `resolves` | `resolve` | `ResolveReceipt` completo (cualquier transición de estado) |
| `resuelto` | `record` | `ChannelRecord` firmado por backend, `payload.event ∈ {pending, cleared, disputed}` |
| `locations` | `location` | sin cambios — la trayectoria del originador sigue fluyendo |
| `solicito-ayuda` | `record` | sin cambios — el SOS original permanece visible; el resolve NO lo borra |

Los clientes que quieran "ocultar" un SOS resuelto en su UI deben
correlacionar `resuelto` con `solicito-ayuda` por `targetSosId`. El backend
**no elimina** el registro original — es append-only.

## Endpoints REST

### Implementados

- `POST /resolve/evidence` — sube imagen, devuelve `{imageHash, storageKey,
  uploadToken, expiresInMs}`.
- `POST /resolve` — envía `ResolveEnvelope`, devuelve `{accepted, status,
  targetSosId, quorumNeeded, quorumSeen, receiptId?, cooldownEndsAt?,
  reason?}`.
- `GET /channels/resuelto/records?since=<ms>` — histórico de transiciones
  (canal oficial, sin auth si el canal es público).

### Futuros

- `GET /resolve/:targetSosId` — recibo actual del target + lista de
  testigos.
- `GET /resolve/evidence/:imageHash` — descarga de imagen (admin auth).
- `POST /resolve/:receiptId/promote` — coordinador fuerza `cleared`
  (admin auth).
- `POST /resolve/:receiptId/dispute` — coordinador fuerza `disputed`.

## Consideraciones operativas

- **Almacenamiento de evidencia** — en prod, Supabase Storage bucket
  `resolve-evidence`. Lifecycle policy sugerida: retener 30 días después
  de `cleared`, retener 1 año para `disputed`.
- **Purga de pending-clears** — el store en memoria tiene tope de 10 000
  entries (FIFO). En prod, depender del `WHERE status='pending'` en
  Postgres; limpiar manualmente con `UPDATE ... SET status='rejected'
  WHERE cooldown_ends_at < now() - interval '7 days'`.
- **Multi-proceso** — el mutex actual es in-process. Para escalar a
  múltiples instancias del backend, reemplazar con advisory locks
  Postgres (`pg_advisory_xact_lock(hashtext(target_sos_id))`) en las
  transiciones `markDisputed` y `markCleared`.
- **Privacidad** — `witnessDeviceIds` se incluye en `ResolveReceipt`
  (para coordinadores), pero NO en el `ChannelRecord` broadcast a
  `resuelto` (que solo lleva `witnessCount` agregado). Revisar antes de
  exponer `/resolve/:targetSosId` públicamente.

## Roadmap sugerido

1. **App móvil del buscador** (mayor dependencia) — sin esto el flujo no
   se ejercita en producción.
2. Endpoint `GET /resolve/:targetSosId` para que la app muestre el
   estado actual antes de co-firmar.
3. Consola mínima del coordinador — solo disputes queue.
4. Reputación persistente.
5. Prueba de presencia BLE firmada.
6. Multi-proceso + advisory locks.
