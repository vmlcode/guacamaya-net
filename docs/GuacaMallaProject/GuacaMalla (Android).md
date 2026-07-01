# GuacaMalla (Android)

App nativa Android (Kotlin + Jetpack Compose) que implementa la malla. Los teléfonos actúan como
**balizas de radio, no como pares**: emiten ráfagas estructuradas al aire y escuchan lo que flota.
No hay conexión que caer, ni credencial que filtrar, ni infraestructura que falle.

Parte de [[GuacaMallaProject]]. Paquete **`net.guacamaya`** (antes `org.sosnet` — ver rebrand en
[[Arquitectura y Decisiones]]). Vive en `android/` dentro del monorepo y es un **proyecto Gradle
autocontenido**: se abre `android/` directo en Android Studio, no la raíz del repo. El detalle del
formato binario está en [[Protocolo y Frame]].

## Dos planos de transporte

| Plano | Radio | Carga | Estado |
|---|---|---|---|
| Control / descubrimiento | BLE 5 Extended Advertising (`Broadcaster` / `Observer`) | el frame de 119 B como service data | ✅ funcionando end-to-end, **verificado en dos teléfonos físicos** |
| Datos ligeros (≤255 B) | Wi-Fi Aware NAN (service discovery, `NanMessenger`) | mismo frame como SSI | 🟡 escrito, no integrado al servicio |
| Datos pesados (>255 B) | Wi-Fi Aware NAN Data Path (`NanDataPath`) | payloads grandes | 🔴 stub |

> En runtime hoy solo corre el plano BLE; el `GuacamayaForegroundService` aún no arranca Wi-Fi Aware.

## Mapa de módulos (`app/src/main/kotlin/net/guacamaya/`)

- `proto/` — `Payload` (codec 22 B), `Flags`, `SosType`, `Crc16`. Puro, bien testeado, sin deps Android.
- `crypto/` — `Identity` (par de llaves sellado en Keystore), `Signer` (Ed25519 vía BouncyCastle).
- `ble/` — `Broadcaster`, `Observer`, `BleConfig` (UUID de servicio, params de advertising), `BleMeshRuntime` (runtime compartido del plano BLE).
- `aware/` — `NanMessenger`, `NanDataPath`, `AwareConfig`.
- `mesh/` — `FloodRouter` (cascada de rechazo), `DedupeCache` (LRU + TTL), `MessageStore` (Room), `NodeCatalog` (un dispositivo = un `node_id`).
- `service/` — `GuacamayaForegroundService` (antes `SosForegroundService`; mantiene las radios vivas en background).
- `ui/` — radar/brújula, mapa de cuadrícula offline, geo y Compose (detalle abajo).
- `util/` — `BatteryHelper` (bucket de batería, sin popup MIUI en arranque).
- `adb/` — `AdbCommandReceiver`: receptor de broadcasts para enrutar comandos de prueba por adb (necesario en MIUI/Xiaomi para arrancar scan/observe desde scripts).

## El stack de Radar / Brújula / Mapa (nuevo)

Reescrito en el sprint de campo de junio 2026 (`/loop` ~21 ticks sobre dos teléfonos reales). **Se
eliminó osmdroid** y se quitó la dependencia de Google Play Services para el render del mapa —
decisión en [[Arquitectura y Decisiones]] §7.

- `ui/GeoGrid.kt` + `GeoProximity.gridPoints` — modelo de **cuadrícula offline en metros** (plano ENU
  este/norte), usuario al centro, una posición por nodo. Reemplazó al mapa de tiles de osmdroid (menos
  RAM, sin WebView, sin descargar tiles). **Nota:** la UI multi-punto (`GridMap.kt`) se eliminó; el
  radar actual es de **objetivo único** (flecha al nodo más cercano, `RadarScreen`/`RadarCompass` en
  `MainActivity`). El modelo `gridPoints` sigue disponible si se reintroduce el plano multi-blip.
- `ui/CompassHeading.kt` — heading geográfico: remapeo por rotación de pantalla
  (`remapCoordinateSystem`), filtro exponencial, `GEOMAGNETIC_ROTATION_VECTOR`, y **fallback
  acelerómetro+magnetómetro** para MTK/Xiaomi. Botón **«Calibrar norte»** con offset persistido por
  dispositivo. Ejes para teléfono vertical (`AXIS_X` + `AXIS_MINUS_Y`).
- `ui/CartesianGeo.kt` — conversión lat/lon ↔ metros ENU (testeada).
- `ui/GeoProximity.kt` — distancia entre nodos con suavizado EMA del GPS + posición por nodo; dentro
  de la incertidumbre del fix muestra **«junto»** en vez de saltar 1–4 m; sub-10 m en cm. Cuando el
  GPS dice «junto», usa el RSSI BLE suavizado como hint (`tocando`, `~1 m`, …).
- `ui/LocationTracker.kt` — fix de GPS vía `FusedLocationProviderClient` (con rechazo de outliers y
  suavizado). **Fallback offline:** `loc/PlatformLocation.kt` usa el `LocationManager` de AOSP
  (GPS/NETWORK/PASSIVE) cuando no hay Google Play Services — ver «Radar offline» abajo.
- `ui/FunctionalProbe.kt` — sonda de diagnóstico que se vuelca por logcat (`nodes`, `frames`,
  `bearing`, `rel=`, `co_loc`, `magnet=…`) para las pruebas adb dual-device.
- `ui/MapViewModel.kt`, `ui/MainActivity.kt` (~934 líneas), `ui/Theme.kt` — UI Compose.

> **Brújula — estado de campo:** Realme calibra bien (`magnet=high`, ~87–109°). El teléfono MIUI
> ("sweet") reporta `magnet=bad` y queda **bloqueado en calibración manual** (figura-8,
> `./scripts/demo.sh compass-miui sweet`). Es el principal pendiente de campo — ver [[Estado y Pendientes]].

### Radar funcional / offline (sprint jun 2026)

Síntoma reportado: «no se ven los puntos en el radar». Causa = tres compuertas, cualquiera vacía el radar:

1. **El modo SOS apagaba el escaneo** (`applyMode(SOS)` mandaba `OBSERVE_OFF`): un equipo emitiendo
   SOS no oía a nadie → `latestNodes` vacío. **Arreglado:** SOS ahora **emite distress Y escucha**
   (`OBSERVE_ON` + `ACTION_START`), así el radar se puebla mientras pides ayuda.
2. **El GPS propio solo venía de Google Play Services** (`FusedLocationProviderClient` en servicio +
   radar). Sin GMS (teléfono de gama baja / de-Googled) el fix era `null` → radar en blanco.
   **Arreglado:** `loc/PlatformLocation.kt` (LocationManager AOSP) como fallback en el servicio
   (estampado del frame) y en el radar (`rememberLiveLocation` siembra last-known al instante y usa
   updates de plataforma cuando `GoogleApiAvailability != SUCCESS`).
3. **Nodos sin fix propio viajan con lat/lon 0,0** y se descartan (`GeoProximity:59`). Inherente:
   un nodo solo es ploteable si su propio frame trae GPS — por eso (2) importa en ambos extremos.

**El radar es de objetivo único** (flecha al más cercano), por decisión de este sprint — no se
reintrodujo el plano multi-blip (el modelo `gridPoints` existe si se quiere después). La estrategia
de **recolección de puntos geográficos es frame-derived**: cada frame firmado trae la lat/lon del
emisor (igual que el ingest zero-trust del backend); offline-nativo, sin servidor ni tiles.

### Modo por defecto = SOS

`MapViewModel._mode` arranca en `MeshMode.SOS` (antes `BOTH`): es una app de emergencia, el botón de
power debe **pedir ayuda primero**. Como SOS ahora también escucha, el radar sigue poblándose. `FIND`
= solo escucha (ahorro de batería); `AMBOS` = presencia (no-crítico) + escucha.

## Identidad

Un par de llaves Ed25519 por instalación, generado de forma perezosa. La semilla privada (32 B) se
cifra con AES-GCM bajo una llave maestra del Android Keystore; el ciphertext vive en SharedPreferences
privadas. La llave pública es la identidad durable del nodo. El `node_id` (4 B en cada frame) son los
primeros 4 B de `SHA-256(pubkey)` — esto **ata el payload a la llave que lo firmó**.

## Persistencia (Room)

`mesh/MessageStore.kt` — DB Room `guacamaya.db` (`GuacamayaDatabase`, tabla `messages`), solo frames
**verificados**. Detalles que importan:

- `MessageEntity` guarda `payloadRaw` (22 B), `pubkey` (32 B), **la firma `sig` (64 B)**, un flag
  `uploaded`, más campos derivados (lat/lon, sosType, flags, rssi, `receivedAt`). La `sig` y `uploaded`
  se añadieron en la migración Room **v3** para el [[IngestClient (Data-Mule Uploader)]] (antes la firma
  no se persistía). Las filas pre-v3 quedan con `sig` vacía y se excluyen de la subida.
- Pruning batcheado: cada `PRUNE_EVERY_INSERTS = 128` inserts conserva las `MAX_STORED_MESSAGES =
  25_000` filas más recientes (antes eran 500). `DEFAULT_RECENT_LIMIT = 2_000` para la UI.
- `MessageDao.observeLatestPerNode` da la última fila por `node_id` (un dispositivo = un pin en
  radar/mapa).

## UI y servicio

`GuacamayaForegroundService` es un foreground service tipo `connectedDevice` (necesario en Android
14+ para mantener BLE vivo con pantalla apagada). Es dueño del Broadcaster/Observer/FloodRouter y
responde a intents `ACTION_START / STOP / OBSERVE_ON / OBSERVE_OFF`. Watchdog legacy en BLE: revisa
cada 30 s y reinicia scan si pasan 60 s sin frame GuacaMalla.

## Restricciones de plataforma

- **minSdk 26** (Wi-Fi Aware desde Android 8.0). target/compileSdk 34. `applicationId net.guacamaya`.
- **Ed25519 vía BouncyCastle 1.78.1**, no `java.security` (el proveedor del JDK solo trae Ed25519
  desde API 33, pero minSdk es 26). Firmar/verificar siempre por `crypto.Signer`.
- El broadcast BLE requiere chip con **Extended Advertising** (`isLeExtendedAdvertisingSupported`);
  si no, `Broadcaster.create` devuelve null.
- Red: **WorkManager 2.9.1** (para el [[IngestClient (Data-Mule Uploader)]]) + `HttpURLConnection` de la
  plataforma (sin OkHttp/Retrofit). `INTERNET`/`ACCESS_NETWORK_STATE` ya estaban en el manifest.

Cómo compilar y correr: [[Build y Entorno]]. Estado y pendientes: [[Estado y Pendientes]].
