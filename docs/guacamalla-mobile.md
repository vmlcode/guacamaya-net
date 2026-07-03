# GuacaMalla Mobile (`guacamalla-mobile/`)

Referencia central del **app mesh de GuacaMalla Net**: malla de mensajería L2 sin conexión para
difusión de SOS, nativa Android (Kotlin + Jetpack Compose). Los teléfonos funcionan como **balizas
de radio, no como pares**: sin pairing, sin handshake, sin intercambio de credenciales. La
autenticidad vive en la capa de aplicación mediante **firmas Ed25519**.

El app es **serverless por diseño** — el backend (`docs/guacamalla-api.md`) es un sink opcional de
data-mule, nunca una dependencia dura.

> Fuente de verdad del código: `guacamalla-mobile/app/src/main/kotlin/net/guacamaya/`.
> Specs profundos (flujos con diagramas, runbook de demo): `guacamalla-mobile/docs/`.
> Instrucciones para Claude Code: `guacamalla-mobile/CLAUDE.md`.
> Sistema de diseño (tokens, color semántico de emergencia): `docs/DESIGN.md`.

---

## 1. Tesis

Las apps de mensajería comerciales abren canales dedicados entre dos endpoints y autentican en la
capa de enlace (pairing, WPA2, …). GuacaMalla descarta ese modelo: el enlace es intencionalmente
abierto — esto es un SOS público, cualquiera en alcance debe recibirlo y retransmitirlo. La
confianza vive en el payload: cada frame de 22 bytes va firmado con Ed25519, y cualquier
manipulación rompe la firma de modo que el siguiente salto descarta el paquete en silencio.

El resultado es una malla asíncrona, oportunista, sin conexión: sin conexión que caer, sin
credencial que filtrar, sin infraestructura que falle.

## 2. Arquitectura de radio

| Plano | Radio | Tamaño payload | Estándar |
|---|---|---|---|
| Control | BLE GAP Broadcaster / Observer | 118 B service data (+1 B TTL = 119 B) | BLE 5 Extended Advertising (`ADV_EXT_IND`, PHY 1M/Coded) |
| Datos (ligero) | Wi-Fi Aware (NAN) | ≤ 255 B | NAN Service Discovery Action Frame |
| Datos (pesado) | Wi-Fi Aware (NAN) | > 255 B | NAN Data Path (NDP), auto-negociado, efímero |

Los siete flujos formales del protocolo (diagramas de secuencia + FSMs) están en
`guacamalla-mobile/docs/protocol-flows.md`.

## 3. Frame BLE (119 B on-wire)

```
[0]        1 B   hop TTL   (mutable — cada relay lo decrementa; FUERA del payload firmado)
[1..22]    22 B  payload firmado (big-endian)
[23..54]   32 B  pubkey Ed25519 del origen
[55..118]  64 B  firma Ed25519 sobre los 22 B del payload

Payload (22 B) — proto/Payload.kt:
  [0..3]   int32   latE7    (lat × 1e7)
  [4..7]   int32   lonE7    (lon × 1e7)
  [8..11]  uint32  tsUnix   (segundos)
  [12..15] 4 B     nodeId   (= SHA-256(pubkey)[0..3])
  [16]     uint8   flags    (bit0 hasHeavy, bit1 critical, bits2-3 battery, bits4-7 hopTtl inicial)
  [17]     uint8   sosType  (0 medical, 1 distress, 2 food, 3 water, 4 shelter, 5 fire, 6 violence, 7 other)
  [18..19] uint16  msgId    (secuencia por nodo; dedupe)
  [20..21] uint16  CRC16-CCITT sobre payload[0..19]
```

El hop TTL vive **fuera** del payload firmado a propósito: si estuviera dentro, la firma del origen
se rompería en el primer salto (re-firmar es imposible — `nodeId` está ligado al pubkey del origen).
Cuando `flags.hasHeavy = 1`, el nombre de servicio NAN se deriva del frame:
`"guacamalla::" + hex(nodeId) + "::" + hex(msgId)` — sin round-trip extra de descubrimiento.

Mapa de bytes completo y ejemplo trabajado: `guacamalla-mobile/docs/payload-binary-layout.md`.
**Estas constantes deben mantenerse byte-idénticas** con `packages/shared/src/mesh/` en el backend
(`guacamalla-api/src/mesh/frame.ts` las espeja para `/ingest`).

## 4. Cripto y cascada de rechazo

- **Ed25519** (RFC 8032) vía BouncyCastle 1.78.1 (`minSdk 26` impide usar la implementación de
  plataforma, que llega en API 33). Un keypair por instalación; la semilla privada de 32 B se cifra
  con AES-GCM (llave en Android Keystore) y vive en `SharedPreferences` privadas.
- **Cascada de rechazo** (`mesh/FloodRouter.kt`, barato primero): longitudes → binding
  `SHA-256(pubkey)[0..3] == nodeId` → CRC16 → skew `|tsUnix − ahora| ≤ 300 s` → `hopTtl > 0` →
  verify Ed25519. **Verify es siempre el último gate** — nunca persistir ni retransmitir antes de
  pasar la cascada completa.
- Modelo de amenazas y limitaciones v1 (sin cifrado, sin revocación, nodeId truncado):
  `guacamalla-mobile/docs/crypto.md`.

## 5. Módulos (`app/src/main/kotlin/net/guacamaya/`)

| Módulo | Qué hace |
|---|---|
| `proto/` | Codec del payload 22 B + CRC16 |
| `crypto/` | Identidad Ed25519, sign/verify |
| `ble/` | Broadcaster (ADV_EXT_IND) + Observer (filtra service UUID **en software**, no `ScanFilter`) |
| `aware/` | NAN messenger + NAN Data Path |
| `mesh/` | FloodRouter, dedupe, persistencia (Room) |
| `service/` | Foreground service que mantiene los radios vivos |
| `ingest/` | Cliente data-mule hacia `POST /ingest` del backend |
| `backend/` | Cliente HTTP/WS de canales oficiales (verificación contra `GET /pubkey`) |
| `ui/` | Jetpack Compose: radar/brújula, mapa de cuadrícula offline |

## 6. Compilar, probar, demo

Proyecto Gradle **autocontenido**: abrir `guacamalla-mobile/` (no la raíz del repo) en Android Studio.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk

./scripts/demo.sh install|tamper|logcat   # helper de demo
python3 scripts/tamper_test.py            # verifica la matemática de tampering en el host
```

Guión de demo de 90 s: `guacamalla-mobile/docs/demo-runbook.md`.

**Plataforma:** minSdk 26 (Wi-Fi Aware), targetSdk 34, Kotlin 1.9.x, AGP 8.5.x, Compose BOM 2024.06.

## 7. Integración con el backend (data-mule)

Cuando el teléfono recupera red, sube los frames recolectados a `POST /ingest`
(payload22 + pubkey32 + sig64 = 118 B, Base64 `NO_WRAP`) — el gate es la firma, **el app nunca
porta llaves de servidor**. Contrato completo, formato del frame de subida y flujo recomendado:
**`docs/guacamalla-api.md`** (§3 y §6).

Trabajo abierto: el `IngestClient` debe persistir también la `sig` de 64 B de los frames recibidos
(`MessageEntity` hoy guarda `payloadRaw` + `pubkey` pero no la firma) para poder re-subir frames
ajenos como mula real.
