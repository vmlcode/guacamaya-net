# guacamalla

GuacaMalla es una red de comunicación de emergencia descentralizada y Offline-First, diseñada para situaciones de catástrofe (terremotos, cortes masivos, colapso de infraestructura) donde internet y las torres celulares dejan de funcionar.

## ¿Cuál es la meta?

Nuestra meta es salvar vidas asegurando que ninguna persona herida o aislada se quede incomunicada en momentos críticos.

## ¿Cómo funciona?

La app convierte teléfonos Android en balizas de radio independientes que emiten frames de 119 bytes firmados con Ed25519 vía Bluetooth (BLE 5). Los mensajes SOS y coordenadas GPS saltan de dispositivo en dispositivo formando una malla descentralizada sin necesidad de servidores ni internet. Cuando un teléfono alcanza una zona con señal actúa como "mula de datos" y sube los frames acumulados al backend opcional, que los re-verifica criptográficamente antes de persistirlos.

El enlace es intencionalmente abierto — un SOS es público, cualquiera en alcance debe recibirlo y retransmitirlo. La confianza vive en el payload: cada frame va firmado con Ed25519 y cualquier manipulación rompe la firma, de modo que el siguiente salto lo descarta silenciosamente.

**El backend es opcional.** La malla funciona 100% offline y sin infraestructura.

## Arquitectura

| Plano | Radio | Payload | Estándar |
|---|---|---|---|
| Control / SOS | BLE GAP Broadcaster/Observer | 22 B payload + 32 B pubkey + 64 B firma | BLE 5 Extended Advertising |
| Datos (ligero) | Wi-Fi Aware (NAN) | ≤ 255 B | NAN Service Discovery |
| Datos (pesado) | Wi-Fi Aware (NAN) | > 255 B | NAN Data Path (NDP), efímero |

El frame de 22 bytes incluye lat/lon (int32 E7), timestamp Unix y tipo de mensaje. La autenticidad se verifica con Ed25519 en cada salto — el nodo receptor nunca confía en el remitente.

## Estructura del repositorio

```
guacamaya-net/
├── android/          # App nativa Android (Kotlin + Jetpack Compose)
│   ├── app/          #   BLE, Wi-Fi Aware, mesh FloodRouter, UI Compose
│   ├── docs/         #   spec del protocolo (flujos, layout de bytes, crypto)
│   └── scripts/      #   helpers build/install/logcat/demo
├── backend/          # Servidor opcional (Bun + TypeScript + Fastify)
│   ├── src/          #   HTTP API + WebSocket + ingest de frames mesh
│   └── supabase/     #   schema SQL
├── packages/
│   └── shared/       #   Tipos y crypto compartidos (ChannelRecord, Ed25519 helpers)
└── docs/
    └── GuacamayaProject/  # Documentación general del proyecto
```

## Inicio rápido — App Android

**Requisitos:**
- Teléfono Android 8.0+ (API 26) con soporte **Wi-Fi Aware**
- JDK 17 y Android Studio (incluye Android SDK 34)

**Compilar e instalar:**

```bash
# Opción A — Android Studio
# File → Open → carpeta android/ → Build → Build APK(s)

# Opción B — línea de comandos (desde android/)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Scripts de demo:**

```bash
./scripts/demo.sh install    # build + adb install en el teléfono conectado
./scripts/demo.sh logcat     # stream colorizado de logcat
./scripts/demo.sh tamper     # genera frame con bit-flip para demostrar rechazo de firma
```

Para la demo completa ver [`android/docs/demo-runbook.md`](android/docs/demo-runbook.md).

## Inicio rápido — Backend (opcional)

**Requisitos:** [Bun](https://bun.sh) ≥ 1.1

```bash
bun install
cp backend/.env.example backend/.env   # ajustar variables según necesidad
bun run dev:backend                    # HTTP + WebSocket en :3000
```

Funciona sin base de datos (modo in-memory por defecto). Para producción:

```bash
bun run keygen    # genera BACKEND_PRIVATE_KEY_HEX y GUACAMAYA_ADMIN_KEY
```

Variables clave en `backend/.env`:

| Variable | Descripción |
|---|---|
| `BACKEND_PRIVATE_KEY_HEX` | Identidad Ed25519 del servidor (estable entre reinicios) |
| `GUACAMAYA_ADMIN_KEY` | Requerida en producción para publicar alertas oficiales |
| `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` | Opcional — sin ellas usa stores en memoria |

## Requisitos de dispositivo

La app requiere **Android 8.0 o superior (API 26)**. Wi-Fi Aware no está disponible en todos los modelos — verificarlo en los ajustes de red del teléfono. BLE funciona en cualquier teléfono con Bluetooth 5.

## Documentación

### Proyecto general

- [Generales del proyecto](./docs/GuacamayaProject/GuacamayaProject.md)
- [Arquitectura y Decisiones](./docs/GuacamayaProject/Arquitectura%20y%20Decisiones.md)
- [Estado y Pendientes](./docs/GuacamayaProject/Estado%20y%20Pendientes.md)

### Android / Protocolo mesh

- [Guacamaya (Android)](./docs/GuacamayaProject/Guacamaya%20(Android).md)
- [Protocolo y Frame](./docs/GuacamayaProject/Protocolo%20y%20Frame.md)
- [IngestClient (Data-Mule Uploader)](./docs/GuacamayaProject/IngestClient%20(Data-Mule%20Uploader).md)
- [`android/docs/protocol-flows.md`](android/docs/protocol-flows.md) — diagramas de secuencia y FSMs
- [`android/docs/payload-binary-layout.md`](android/docs/payload-binary-layout.md) — mapa de bytes del frame de 119 B
- [`android/docs/crypto.md`](android/docs/crypto.md) — modelo de firmas Ed25519 y amenazas
- [`android/docs/demo-runbook.md`](android/docs/demo-runbook.md) — guión de demo de 90 segundos

### Backend

- [Backend Data-Mule](./docs/GuacamayaProject/Backend%20Data-Mule.md)
- [Build y entorno](./docs/GuacamayaProject/Build%20y%20Entorno.md)
- [Downlink Alertas Oficiales](./docs/GuacamayaProject/Downlink%20Alertas%20Oficiales.md)
- [Resolve y Confirmación de Rescate](./docs/GuacamayaProject/Resolve%20y%20Confirmacion%20de%20Rescate.md)
- [Seguridad Backend](./docs/GuacamayaProject/Seguridad%20Backend.md)
- [`backend_final.md`](backend_final.md) — referencia completa de endpoints para integración Android ↔ backend

## Enlace

- [https://guacamalla.org](https://guacamalla.org)

## Equipo

- Rodrigo Rivas — Product + UI/UX + Frontend · [LinkedIn](https://www.linkedin.com/in/rodrigorivasco)
- Victor Maldonado (Pipo) — Fullstack + Mobile Native · [LinkedIn](https://www.linkedin.com/in/vmlcode/)
- David Gonzalez — Backend + Mobile Native + QA · [LinkedIn](https://www.linkedin.com/in/david-gonzalez-dev2012/)
- Jaime Stalislav — Backend + Mobile Native + QA
- Gustavo Chacon — Frontend · [LinkedIn](https://www.linkedin.com/in/gustavoachaconm)
- Carlos Ramirez — PM + Plataforma · [LinkedIn](https://www.linkedin.com/in/carlosjramirez/)
- Jose Gutierrez — Frontend · [LinkedIn](https://www.linkedin.com/in/jjgutierrezr/)
- Cristopher Avila — Frontend · [LinkedIn](www.linkedin.com/in/cristopher-adrián-ávila-lópez-937994402)

## Países participantes

- Venezuela
- España
- Mexico
- Colombia

## Video demo

*(próximamente)*

## Licencia

[GuacaMalla](https://guacamalla.org) es software de código abierto bajo la licencia **MIT**. Ver [LICENSE](LICENSE) para más detalles.

## Créditos

Desarrollado y mantenido por el Equipo GuacaMalla. Para contribuir visita [GuacaMalla en GitHub](https://github.com/vmlcode/guacamaya-net).
