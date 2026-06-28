# GuacaMalla Net

Malla de mensajería L2 sin conexión para difusión de SOS. Los teléfonos
funcionan como balizas de radio, no como pares: **sin pairing, sin handshake,
sin intercambio de credenciales**. Dos planos transportan los datos — BLE para
descubrimiento + telemetría, Wi-Fi Aware (NAN) para cargas medianas y pesadas —
y la autenticidad se traslada de la capa de enlace a la capa de aplicación
mediante **firmas Ed25519**.

---

## Tesis

Las apps de mensajería comerciales abren canales dedicados entre dos
endpoints y autentican en la capa de enlace (pairing, WPA2, …). GuacaMalla
descarta ese modelo. El enlace es intencionalmente abierto — esto es un SOS
público, cualquiera en alcance debe recibirlo y retransmitirlo. La confianza
vive en la capa de payload: cada frame de 22 bytes va firmado con Ed25519, y
cualquier manipulación rompe la firma de modo que el siguiente salto
silenciosamente descarta el paquete.

El resultado es una malla asíncrona, oportunista, sin conexión. Los teléfonos
emiten ráfagas estructuradas al aire y escuchan lo que flota. Sin conexión que
caer, sin credencial que filtrar, sin infraestructura que falle.

---

## Arquitectura

| Plano | Radio | Tamaño payload | Estándar |
|---|---|---|---|
| Control | BLE GAP Broadcaster / Observer | 22 B + 32 B pubkey + 64 B sig (118 B service data) | BLE 5 Extended Advertising (`ADV_EXT_IND`, PHY 1M/Coded) |
| Datos (ligero) | Wi-Fi Aware (NAN) | ≤ 255 B | NAN Service Discovery Action Frame |
| Datos (pesado) | Wi-Fi Aware (NAN) | > 255 B | NAN Data Path (NDP), auto-negociado, efímero |

Ver [`docs/protocol-flows.md`](docs/protocol-flows.md) para los siete flujos
formales con diagramas de secuencia y FSMs.

---

## Compilar el APK

### Requisitos

- **JDK 17** (`java --version` muestra 17.x)
- **Android SDK** con `platform-android-34` y `build-tools;34.0.0`
  - Ruta más fácil: instalar **Android Studio**, que incluye ambos. En la
    primera apertura del proyecto traccionará el resto automáticamente.
- **Python 3** (solo para el script opcional de demo de tampering)

### Opción A — Android Studio (recomendado)

1. Abrir esta carpeta en Android Studio: `File → Open → GuacaMalla`.
2. Esperar al Gradle sync (la primera vez descarga dependencias).
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

### Opción B — Línea de comandos

```bash
./gradlew :app:assembleDebug
```

APK en `app/build/outputs/apk/debug/app-debug.apk`.

> Si `./gradlew` se queja de que falta el wrapper, ejecutar una vez
> `gradle wrapper --gradle-version 8.7` (requiere `gradle` del sistema),
> luego reintentar.

### Instalar en un teléfono (API 26+, con Wi-Fi Aware)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Para la demo se recomiendan dos teléfonos (un broadcaster y un observer).

---

## Demo runner (build + instalación en un paso)

```bash
./scripts/demo.sh install     # build del APK + adb install en el teléfono conectado
./scripts/demo.sh tamper      # generar frame manipulado, push a /sdcard
./scripts/demo.sh logcat      # stream colorizado de logcat del teléfono
```

Narrativa completa con tiempos — ver [`docs/demo-runbook.md`](docs/demo-runbook.md).

### Verificar la matemática de tampering en el host

```bash
python3 scripts/tamper_test.py
```

Imprime `verify=True` para el frame válido y `verify=False` para el
manipulado (un bit flipeado en el byte 5 del payload). Escribe
`/tmp/guacamaya_test_frames.json`.

---

## Documentación

- [`docs/protocol-flows.md`](docs/protocol-flows.md) — los siete flujos formales
  del protocolo (diagramas de secuencia mermaid + FSMs).
- [`docs/payload-binary-layout.md`](docs/payload-binary-layout.md) — mapa de
  bytes del frame BLE service-data de 118 B.
- [`docs/crypto.md`](docs/crypto.md) — alcance del firmado Ed25519, manejo de
  claves, modelo de amenazas.
- [`docs/demo-runbook.md`](docs/demo-runbook.md) — guión de demo de 90 s para
  el operador.

---

## Estructura del repo

```
GuacaMalla/
├── README.md
├── docs/                       # spec — entregable para el jurado
├── app/                        # proyecto Gradle Android Studio
│   └── src/main/kotlin/net/guacamaya/
│       ├── crypto/             # identidad Ed25519, sign/verify
│       ├── proto/              # codec payload 22 B + CRC16
│       ├── ble/                # Broadcaster (ADV_EXT_IND, 1M/Coded) + Observer
│       ├── aware/              # NAN messenger + NAN Data Path
│       ├── mesh/               # FloodRouter, dedupe, persistencia
│       ├── service/            # foreground service mantiene radios vivos
│       └── ui/                 # Jetpack Compose + mapa OSMDroid
├── scripts/
│   ├── demo.sh                 # helper build + install + tamper + logcat
│   ├── tamper_test.py          # inyección de bit-flip para demo de rotura de firma
│   └── logcat_pretty.py        # filtro colorizado de logcat
├── gradlew / gradlew.bat
├── gradle/                     # wrapper + version catalog (libs.versions.toml)
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Plataforma

- **Min SDK**: 26 (Wi-Fi Aware desde Android 8.0).
- **Target SDK**: 34.
- **Kotlin**: 1.9.x. **AGP**: 8.5.x. **Compose BOM**: 2024.06.
- **Crypto**: BouncyCastle 1.78.1 (Ed25519 por debajo de API 33).
- **Mapa**: OSMDroid (offline-capable).

---

## Licencia

Por definir antes de la entrega.
