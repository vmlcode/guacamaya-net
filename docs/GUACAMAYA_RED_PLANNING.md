# 🦜 Guacamaya Red — Planning

> Red de comunicación de emergencia para desastres (terremotos), con backend central,
> sincronización por internet, SMS y **mesh device-to-device**.
> Solución real para escenarios donde la infraestructura de comunicaciones falla parcial o totalmente.

---

## 0. Decisión clave: por qué NO construimos sobre Briar

Briar fue el punto de partida que evaluamos (mensajería P2P por mesh, sin servidores, usada por activistas). Es excelente en lo suyo, pero **su modelo es opuesto al de Guacamaya Red**. Construir encima de Briar sería pelear contra el framework en cada pieza.

| Pieza de Guacamaya Red | Briar | Veredicto |
|---|---|---|
| **Backend central con canales** | No existe. Briar es P2P puro, sin servidor por diseño/filosofía | ❌ choque de fondo |
| **Fetch por internet (HTTP/WS)** | Usa solo Tor para sincronizar entre contactos, no fetch a un servidor | ❌ no aplica |
| **Sync por SMS** | No existe | ❌ habría que construirlo igual |
| **Canales abiertos (cualquiera entra)** | Requiere agregar contactos mutuos antes de comunicarse | ❌ no sirve para desconocidos en un desastre |
| **Subida data-mule a backend** | No hay backend al cual subir | ❌ no aplica |
| **Mesh device-to-device** | Producción, sólido (Bluetooth/WiFi) | ✅ lo único que sí encaja |

**De las 6 piezas del proyecto, Briar solo resuelve 1 (el mesh), y con un modelo distinto** (sincroniza entre *contactos previamente agregados*, no entre desconocidos en canales abiertos).

Razones adicionales que lo descartan para este proyecto:
- **Lenguaje/plataforma:** Briar es Java/Android con un codebase grande. Nuestro equipo es **JS/TS** → curva de aprendizaje alta y lenta para un hackathon.
- **Modelo de contactos:** Briar exige conocerse y agregarse antes de comunicar. En un terremoto eres *un sobreviviente rodeado de desconocidos*; ese modelo no aplica.
- **Sin concepto de servidor, canales públicos, SMS ni ingesta:** todo lo central de Guacamaya habría que construirlo desde cero *encima* de una arquitectura que empuja en dirección contraria.

**Qué SÍ tomamos de Briar:** su idea de sincronización **store-and-forward (protocolo Bramble)** — guardar todo lo que se ve y reenviarlo cuando hay un encuentro. Eso lo replicamos como **concepto** en nuestro log de gossip (ver §4), no como código.

> **Conclusión:** Guacamaya Red se construye desde cero en JS/TS (Node + React Native/Expo), Android-first, inspirado en el modelo store-and-forward de Briar pero sin depender de su código ni de su arquitectura P2P-sin-servidor.

---

## 1. Problema

En un desastre (ej. terremoto en Venezuela) la infraestructura de comunicaciones se degrada por capas:

| Estado de la red | Qué sigue funcionando |
|---|---|
| Internet caído, celular OK | SMS |
| Internet + celular caídos, hay otros teléfonos cerca | Mesh BT/WiFi |
| Todo caído, dispositivo aislado | Datos en caché local |

La gente necesita información crítica y confiable: **dónde hay refugio, agua, ayuda médica; quién está bien; dónde hay personas atrapadas.** Las apps de mensajería normales asumen internet y contactos previos. Guacamaya Red no.

---

## 2. Arquitectura general

```
                  ┌─────────────────────────┐
                  │     BACKEND CENTRAL      │
                  │  - Canales (firmados)    │
                  │  - HTTP / WebSocket      │
                  │  - Gateway SMS           │
                  └───────────┬─────────────┘
                              │
            ┌─────────────────┼─────────────────┐
            │ internet        │ SMS             │ internet
            ▼                 ▼                 ▼
      ┌──────────┐      ┌──────────┐      ┌──────────┐
      │ Device A │◄────►│ Device B │◄────►│ Device C │   ← propagación
      └──────────┘ mesh └──────────┘ mesh └──────────┘     mesh (BLE / Nearby)
       (con datos)       (solo SMS)        (aislado, recibe por mesh)
```

**Principio central:** todo dato es un registro inmutable con `id` único y `timestamp`. Llegue por internet, SMS o mesh, se fusiona en el log local por **unión + dedupe por id**. La ruta no importa.

### Flujo bidireccional — "data mule" (mesh → backend)

El sync no es solo de bajada. Un dispositivo que recogió reportes por mesh, al recuperar internet o vía SMS, los **empuja de vuelta al backend**. La red se "cura sola".

```
Device aislado            Device con internet          Backend
(reporta offline)  ──mesh──►  (recoge y carga) ──────►  (ingesta + dedupe por id)
                                                          → cola de moderación
```

Es **el mismo protocolo de sync**, solo cambia la dirección. El backend hace el mismo *merge por unión + dedupe por id* que los dispositivos. Una sola persona que alcance señal drena hacia arriba todo lo reportado offline por la comunidad.

**Requisitos de la subida:**
- Los registros subidos siguen `verified: false`. El backend **nunca los firma como oficiales** automáticamente → van a **moderación** o se **agregan** ("47 reportes de ayuda en zona X").
- **Anti-spam/Sybil:** rate limiting por origen + identidad pseudónima del device (llave self-signed) para detectar duplicados/abuso.

---

## 3. Stack técnico

| Componente | Tecnología | Notas |
|---|---|---|
| Backend | Node + TypeScript (Fastify) + WebSocket (`ws`) | Un solo lenguaje en todo el stack |
| App última milla | React Native + **Expo Dev Client** | NO managed — necesitamos módulos nativos (BLE/SMS) |
| Almacenamiento local | SQLite (`expo-sqlite`) o MMKV | El log de gossip vive aquí |
| Mesh | BLE doble rol **o** Google Nearby Connections | A decidir en el spike (Fase 0) |
| SMS | Gateway backend (Twilio o Android-gateway) + API SMS Android en device | Solo Android puede leer/enviar SMS programáticamente |
| Firmas | Ed25519 (`@noble/ed25519`) | Backend firma canales oficiales |
| Plataforma | **Android-first** | Venezuela = parque mayoritariamente Android gama baja |

### ¿Por qué NO Briar como base?
Ver **§0** al inicio del documento para el análisis completo. En resumen: el diseño de Guacamaya es **cliente-servidor + relay mesh**; Briar es **P2P puro sin servidor** → modelos opuestos. Solo reutilizamos su **concepto** store-and-forward, no su código.

---

## 4. Modelo de datos — el log de gossip (corazón del sistema)

Cada dato que viaja por la red:

```ts
interface ChannelRecord {
  id: string;          // hash único → dedupe
  channel: string;     // "alertas" | "refugios" | "estoy-bien" | "ayuda" | ...
  timestamp: number;   // momento del evento (contexto temporal)
  ttl: number;         // saltos restantes en el mesh (evita vida infinita)
  author: string;      // "backend" | "device-<id>"
  verified: boolean;   // true = firmado por backend; false = reporte comunidad
  payload: unknown;    // contenido del mensaje
  sig?: string;        // firma Ed25519 (solo registros oficiales)
}
```

### Reglas
1. **Inmutable + append-only.** Nunca se edita un registro; se crea uno nuevo.
2. **Merge por unión, dedupe por `id`.** Al sincronizar dos nodos, se transfieren solo los `id` faltantes.
3. **Confianza:** canales oficiales firmados por el backend (la app trae la llave pública embebida). Reportes de comunidad → `verified: false`, marcados visualmente como "no confirmado". **Esto evita inyección de desinformación durante el desastre.**
4. **TTL:** cada salto mesh decrementa el TTL; al llegar a 0 deja de propagarse.

### Protocolo de sync entre dos nodos
1. Intercambian resumen de qué tienen (lista de `id` o bloom filter por canal desde cierto `timestamp`).
2. Cada uno pide los `id` que le faltan.
3. Transferencia del diff.
4. Verificación de firmas + merge al log local.

---

## 5. Restricciones reales por transporte

### SMS (~140 bytes/segmento)
- **No** sincroniza canales completos.
- Sirve para: **alertas críticas + titulares recientes** y zonas con celular pero sin datos.
- Requiere codificación compacta binaria (no JSON crudo).
- Es el "salvavidas mínimo".

### Mesh (BLE / Nearby)
- BLE doble rol (peripheral + central) = riesgo técnico #1.
- `react-native-ble-plx` solo hace rol central → para anunciarse se necesita otra librería (Android-only).
- Alternativa más robusta en Android: **Google Nearby Connections** (combina BT + WiFi automáticamente).
- **Decisión final en Fase 0.**

### Internet (HTTP/WebSocket)
- Sync completo y en tiempo real cuando hay datos.
- Sin complicaciones.

---

## 6. Plan por fases (hackathon)

### ⚠️ Fase 0 — Spike de mesh (PRIMERO)
**Objetivo:** probar que 2 teléfonos Android se descubren y se pasan datos.
- [ ] Probar BLE doble rol (`react-native-ble-plx` + advertiser)
- [ ] Probar Google Nearby Connections
- [ ] Decidir transporte definitivo
- [ ] **Criterio de éxito:** transferir un registro entre 2 Android sin internet

> Sin esta fase no hay proyecto. Es lo que decide la viabilidad técnica.

### Fase 1 — Backend + modelo de datos
- [ ] Monorepo (`backend/`, `app/`, `packages/shared/`)
- [ ] Modelo `ChannelRecord` en `packages/shared/`
- [ ] Canales en backend, registros firmados con Ed25519
- [ ] Endpoints HTTP + WebSocket
- [ ] Endpoint de ingesta (`POST /ingest`) para registros de comunidad (mesh → backend) + dedupe por id
- [ ] Generación/gestión del par de llaves del backend

### Fase 2 — App: online + render
- [ ] App Expo dev-client corriendo en Android
- [ ] Log local (SQLite/MMKV) con merge + dedupe
- [ ] Conexión WebSocket → recibir y mostrar canales
- [ ] Verificación de firmas en cliente
- [ ] UI: lista de canales, badge "verificado / no confirmado"

### Fase 3 — SMS
- [ ] Gateway SMS en backend
- [ ] Codificación compacta binaria de registros
- [ ] Envío/lectura de SMS en Android
- [ ] Flujo: SMS → número → respuesta con titulares críticos

### Fase 4 — Mesh
- [ ] Implementar protocolo de sync de gossip sobre transporte de Fase 0
- [ ] TTL + propagación multi-salto
- [ ] Demo de 3 saltos (A → B → C sin internet)
- [ ] **Subida "data mule":** device con internet sube al backend lo recogido por mesh
- [ ] Moderación/agregación de reportes de comunidad en backend

### Fase 5 — Demo de desastre
- [ ] Escenario: internet ON → todo fluye
- [ ] Apagar internet → SMS sigue funcionando
- [ ] Apagar celular → mesh propaga entre teléfonos
- [ ] Dispositivo aislado recibe datos vía mesh de un vecino

---

## 7. Estructura del monorepo (propuesta)

```
guacamaya-red/
├── backend/                # Node + TS + Fastify + WS + gateway SMS
│   ├── src/
│   │   ├── channels/       # lógica de canales
│   │   ├── crypto/         # firma Ed25519
│   │   ├── sms/            # gateway
│   │   └── ws/             # websocket
│   └── package.json
├── app/                    # Expo dev-client (Android)
│   ├── src/
│   │   ├── store/          # log local + merge/dedupe
│   │   ├── transports/     # internet | sms | mesh
│   │   ├── crypto/         # verificación de firmas
│   │   └── ui/             # canales, alertas
│   └── package.json
├── packages/
│   └── shared/             # ChannelRecord, protocolo de sync, codecs
└── package.json            # workspace root
```

---

## 8. Canales iniciales (ejemplo)

| Canal | Autor | Contenido |
|---|---|---|
| `alertas` | backend (firmado) | Réplicas, evacuaciones, peligros |
| `refugios` | backend (firmado) | Ubicación, capacidad, recursos |
| `ayuda-medica` | backend (firmado) | Puntos de atención |
| `estoy-bien` | comunidad | Reportes "estoy a salvo" |
| `solicito-ayuda` | comunidad | "personas atrapadas en X" |

---

## 9. Riesgos y mitigaciones

| Riesgo | Impacto | Mitigación |
|---|---|---|
| BLE doble rol no funciona en RN | Alto | Spike Fase 0 + plan B (Nearby Connections) |
| SMS demasiado limitado | Medio | Codificación binaria + solo titulares críticos |
| Desinformación inyectada | Alto | Firmas Ed25519 + badge "no confirmado" |
| iOS sin soporte mesh/SMS | Bajo | Android-first (decisión consciente) |
| Batería en mesh continuo | Medio | Anuncios intermitentes + TTL |

---

## 10. Próximos pasos inmediatos

1. **Iniciar el spike de mesh (Fase 0)** — decide la viabilidad.
2. **Crear el repo/monorepo nuevo** — este proyecto NO se construye sobre Briar.
3. Definir el `ChannelRecord` final en `packages/shared/`.

---

*Documento de planning — Guacamaya Red. Iterar según resultados del spike de Fase 0.*
