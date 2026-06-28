# Estado y Pendientes

Foto del estado de [[GuacamayaProject]] al **2026-06-28** (rama `develop`, monorepo consolidado).

## Qué funciona

### [[Guacamaya (Android)]]
- ✅ Plano BLE end-to-end: firmar → emitir frame de 119 B → observar → cascada de rechazo → persistir → retransmitir multi-salto.
- ✅ **BLE mesh bidireccional verificado en dos teléfonos físicos** (`device-test` PASS, `nodes=2 frames=47`). Ya no es solo "build + smoke".
- ✅ Identidad Ed25519 real sellada en Keystore.
- ✅ Ubicación GPS real en el payload + sonda ENU (lat/lon/acc/bearing/`rel=`/`co_loc`).
- ✅ TTL de salto que decrementa por relay y para en 0 (byte sin firmar al frente). Ver [[Protocolo y Frame]].
- ✅ **Radar + brújula + mapa de cuadrícula offline** nuevos (`CompassHeading`, `GeoProximity`, `GridMap`); **osmdroid eliminado** y quitada la dependencia de GMS para el render. Ver [[Guacamaya (Android)]].
- ✅ Pruning de la DB Room (conserva 25 000 filas, batcheado cada 128 inserts).
- ✅ **`IngestClient` (data-mule uploader)** implementado: persiste la firma (migración Room v3), arma el frame de 118 B y hace `POST /ingest` vía WorkManager al recuperar red. Build + tests JVM verdes. **Pata de aceptación del backend verificada** contra server real (ingesta/dedup/rechazo/locations); falta el smoke en dispositivo. Ver [[IngestClient (Data-Mule Uploader)]].
- ✅ Tests unitarios verdes (codec, crypto, geo/compass/proximity, ingest).

### [[Backend Data-Mule]]
- ✅ `POST /ingest` zero-trust verificado contra server real (válido ingesta; alterado/forjado rechazado).
- ✅ Histórico de ubicación derivado del frame (`GET /locations`, WS `locations`); sin endpoint JSON confiado.
- ✅ Canales oficiales firmados + WebSocket por canal.
- ✅ **Endurecimiento de seguridad**: API keys (admin/read/ws), CORS allowlist, helmet, rate-limits por ruta, comparación timing-safe, `bun run keygen`. Ver [[Seguridad Backend]].
- ✅ **Flujo Resolve** implementado y testeado (quórum M-de-N, anti-troll, canal `resuelto`). Ver [[Resolve y Confirmacion de Rescate]].
- ✅ `bun test` → 23 pass / 0 fail. `packages/shared` corre con fallback en memoria sin Supabase.

## Parcial / stub

- 🟡 **Brújula MIUI ("sweet")**: reporta `magnet=bad`, bloqueada en calibración manual (figura-8). Realme calibra bien. Principal pendiente de **campo**.
- 🟡 **Wi-Fi Aware no integrado**: `NanMessenger` escrito pero el `GuacamayaForegroundService` no lo arranca. Hoy solo corre BLE.
- 🔴 **NAN Data Path** (`NanDataPath`): stub, payloads pesados devuelven error.
- 🟡 **Resolve sin clientes**: backend listo, pero faltan la app del buscador y la consola del coordinador (todo el flujo de campo). Ver [[Resolve y Confirmacion de Rescate]].

## Trabajo abierto (siguiente)

- [ ] **Smoke en dispositivo del `IngestClient`**: la pata de aceptación del backend ya está
      verificada (commit `eee3508`); falta el camino en hardware (app → BLE → WorkManager → POST a
      `10.0.2.2`). Ver [[IngestClient (Data-Mule Uploader)]].
- [ ] **Fallback de ubicación sin Google Play Services**: el fix GPS aún usa `FusedLocationProviderClient` (GMS). Alternativa robusta para gama baja sin GMS: `LocationManager` de plataforma.
- [ ] **Calibrar/robustecer brújula MIUI** en campo (`functional-compass` con Δheading ≈ 0° en paralelo).
- [ ] **Integrar Wi-Fi Aware** al servicio (publish/subscribe del `NanMessenger`).
- [ ] **Clientes de Resolve**: app del buscador + consola del coordinador (ver nota dedicada).
- [ ] **Endurecer `/ingest`**: rate-limit por origen además del global; moderación de reportes de comunidad.
- [ ] **UUID de servicio BLE** en `BleConfig` sigue siendo placeholder — cambiar antes de uso productivo.

## Riesgos conocidos

- **Sync de wire-format de 3 vías** (`net.guacamaya.proto.*` ↔ `backend/src/mesh/frame.ts` ↔
  `packages/shared/src/mesh/constants.ts`): si cambia el formato en uno, los otros rechazan frames
  válidos **en silencio**. Mantener los tres sincronizados y correr los tests de ambos lados. Ver
  [[Protocolo y Frame]].
- El mesh real (BLE/GPS/relay) solo se confirma con **dos teléfonos físicos** (ya hecho para BLE; la
  brújula MIUI sigue pendiente).
- `init-sosnet` quedó atrasada respecto a `develop` — no trabajar features nuevas ahí.
