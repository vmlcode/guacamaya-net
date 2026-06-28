# Guacamaya (Android)

App nativa Android (Kotlin + Jetpack Compose) que implementa la malla. Los teléfonos actúan como
**balizas de radio, no como pares**: emiten ráfagas estructuradas al aire y escuchan lo que flota.
No hay conexión que caer, ni credencial que filtrar, ni infraestructura que falle.

Parte de [[GuacamayaProject]]. El detalle del formato binario está en [[Protocolo y Frame]].

## Dos planos de transporte

| Plano | Radio | Carga | Estado |
|---|---|---|---|
| Control / descubrimiento | BLE 5 Extended Advertising (`Broadcaster` / `Observer`) | el frame de 119 B como service data | ✅ funcionando end-to-end |
| Datos ligeros (≤255 B) | Wi-Fi Aware NAN (service discovery, `NanMessenger`) | mismo frame como SSI | 🟡 escrito, no integrado al servicio |
| Datos pesados (>255 B) | Wi-Fi Aware NAN Data Path (`NanDataPath`) | payloads grandes | 🔴 stub |

> En runtime hoy solo corre el plano BLE; el `SosForegroundService` aún no arranca Wi-Fi Aware.

## Mapa de módulos (`app/src/main/kotlin/org/sosnet/` — paquete Kotlin pendiente de rebrand a Guacamaya)

- `proto/` — `Payload` (codec 22 B), `Flags`, `SosType`, `Crc16`. Puro, bien testeado, sin deps Android.
- `crypto/` — `Identity` (par de llaves sellado en Keystore), `Signer` (Ed25519 vía BouncyCastle).
- `ble/` — `Broadcaster`, `Observer`, `BleConfig` (UUID de servicio, parámetros de advertising).
- `aware/` — `NanMessenger`, `NanDataPath`, `AwareConfig`.
- `mesh/` — `FloodRouter` (cascada de rechazo), `DedupeCache` (LRU + TTL), `MessageStore` (Room).
- `service/` — `SosForegroundService` (mantiene las radios vivas en background).
- `ui/` — `MainActivity` + `MapViewModel` (Compose, mapa OSMDroid offline).

## Identidad

Un par de llaves Ed25519 por instalación, generado de forma perezosa. La semilla privada (32 B) se
cifra con AES-GCM bajo una llave maestra del Android Keystore; el ciphertext vive en
SharedPreferences privadas. La llave pública es la identidad durable del nodo. El `node_id` (4 B que
viajan en cada frame) son los primeros 4 B de `SHA-256(pubkey)` — esto **ata el payload a la llave
que lo firmó**.

## UI y servicio

`SosForegroundService` es un foreground service tipo `connectedDevice` (necesario en Android 14+
para mantener BLE/Wi-Fi vivos con pantalla apagada). Es dueño del Broadcaster/Observer/FloodRouter y
responde a intents `ACTION_START / STOP / OBSERVE_ON / OBSERVE_OFF`.

`MainActivity` + `MapViewModel`: una sola pantalla Compose con dos toggles (Broadcast / Observe) que
disparan esos intents, más un mapa OSMDroid con los pines de SOS recibidos. Solo se persisten y
muestran frames **verificados**.

## Restricciones de plataforma

- **minSdk 26** (Wi-Fi Aware desde Android 8.0). target/compileSdk 34.
- **Ed25519 vía BouncyCastle 1.78.1**, no `java.security` (el proveedor del JDK solo trae Ed25519
  desde API 33, pero minSdk es 26). Firmar/verificar siempre por `crypto.Signer`.
- El broadcast BLE requiere chip con **Extended Advertising** (`isLeExtendedAdvertisingSupported`);
  si no, `Broadcaster.create` devuelve null.

Cómo compilar y correr: [[Build y Entorno]]. Estado y pendientes: [[Estado y Pendientes]].
