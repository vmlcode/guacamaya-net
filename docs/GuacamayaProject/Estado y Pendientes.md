# Estado y Pendientes

Foto del estado de [[GuacamayaProject]] al 2026-06-27.

## Qué funciona

### [[Guacamaya (Android)]]
- ✅ Plano BLE end-to-end: firmar → emitir frame de 119 B → observar → cascada de rechazo → persistir → retransmitir multi-salto.
- ✅ Identidad Ed25519 real sellada en Keystore.
- ✅ Ubicación GPS real en el payload (FusedLocationProviderClient), antes hardcodeada a (0,0).
- ✅ TTL de salto que decrementa por relay y para en 0 (byte sin firmar al frente). Ver [[Protocolo y Frame]].
- ✅ Pruning de la DB Room a 500 filas para dispositivos de poca RAM.
- ✅ UI Compose + mapa OSMDroid; tests unitarios de codec y crypto en verde.
- ✅ Build verde (APK ~13 MB) tras arreglar el entorno SDK/JDK — ver [[Build y Entorno]].

### [[Backend Data-Mule]]
- ✅ `POST /ingest` zero-trust verificado contra server real (válido ingesta; alterado/forjado rechazado).
- ✅ Bug de env var de la llave arreglado (identidad estable entre reinicios).
- ✅ `packages/shared` con tests; backend corre con fallback en memoria sin Supabase.

## Parcial / stub

- 🟡 **Wi-Fi Aware no integrado**: `NanMessenger` está escrito pero el `SosForegroundService` no lo arranca. Hoy solo corre BLE.
- 🔴 **NAN Data Path** (`NanDataPath`): stub, payloads pesados devuelven error.
- 🔴 **SMS**: `backend/src/sms/` vacío (solo `.gitkeep`). El plan original lo contemplaba; no implementado.

## Trabajo abierto (siguiente)

- [ ] **`IngestClient` en Kotlin**: el data-mule de subida no existe aún. La app debería juntar frames recibidos y `POST /ingest` cuando recupere internet. Contrato en [[Backend Data-Mule]].
- [ ] **Fallback de ubicación sin Google Play Services**: FusedLocation depende de GMS; muchos teléfonos de gama baja del mercado objetivo no lo traen → devuelve null. Alternativa robusta: `LocationManager` de plataforma.
- [ ] **Integrar Wi-Fi Aware** al servicio (publish/subscribe del `NanMessenger`).
- [ ] **Script `bun run keygen`** en el backend (el `.env.example` lo menciona pero no existe; además referencia `npm`, debería ser `bun`).
- [ ] **Endurecer `/ingest`**: rate-limit por origen además del global; considerar agregación/moderación de reportes de comunidad.
- [ ] **UUID de servicio BLE** en `BleConfig` es placeholder — cambiar antes de cualquier uso productivo.

## Riesgos conocidos

- Solo verificado por compilación + tests + smoke; **el mesh real (BLE/GPS/relay) solo se confirma con dos teléfonos físicos**.
- El layout binario de `frame.ts` (backend) debe seguir a `proto/Payload.kt` (app): si cambia el formato de un lado, `/ingest` rechaza frames válidos en silencio. Mantener ambos en sync.
