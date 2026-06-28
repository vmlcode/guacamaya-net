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
- ✅ **Downlink de alertas oficiales** + alcanzabilidad: `BackendClient` descarga `/channels/:id/records` y **verifica la firma** (esquema oficial, `OfficialRecordVerifier`); banner en la UI; `BACKEND_BASE_URL` configurable (debug LAN override + cleartext de debug). Tests JVM verdes; falta smoke en dispositivo. Ver [[Downlink Alertas Oficiales]].
- ✅ Tests unitarios verdes (codec, crypto, geo/compass/proximity, ingest, backend/alertas).
- ✅ **Sync de Gradle portable**: se quitó el `org.gradle.java.home` absoluto del `gradle.properties` (rompía el sync en máquinas sin ese JDK exacto; commit `3756af4`). JDK por dev, 17–21. Ver [[Build y Entorno]].

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

## Próximos pasos (wiring BE↔app — priorizados)

El uplink ([[IngestClient (Data-Mule Uploader)]]) y el downlink ([[Downlink Alertas Oficiales]]) ya
están en código y verificados *headless*. Lo que falta, en orden:

1. [ ] **Smoke end-to-end en hardware** (lo único que bloquea declarar el wiring "hecho"):
   - Levantar el backend en un laptop (`bun run dev:backend`) y obtener su **IP de LAN**.
   - Compilar debug apuntando ahí: `./gradlew :app:assembleDebug -PBACKEND_BASE_URL=http://<IP-LAN>:3000` (o emulador con el default `10.0.2.2`).
   - **Uplink**: Observe recoge un frame BLE → al recuperar red el `IngestUploadWorker` hace POST → confirmar `{ ingested }` y `GET /locations`.
   - **Downlink**: crear una alerta oficial (`POST /channels/alertas/records` con admin key) → en la app debe aparecer el **banner de alertas verificadas**.
2. [ ] **WebSocket `/ws`** en la app: suscribir `solicito-ayuda` para SOS comunitarios en vivo (sin polling). Es el siguiente nivel de wiring. Ver `backend_final.md` §4.10 y [[Downlink Alertas Oficiales]].
3. [ ] **Reconciliar `backend_final.md`** (doc del equipo, desactualizado): dice que el `IngestClient` no existe y usa `org.sosnet`/`BACKEND_BASE_URL`. Alinear con la realidad.

## Trabajo abierto (backlog)

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
