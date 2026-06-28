# Guacamalla Net — Backend API (integración móvil)

Documento de referencia de **cada endpoint del backend** orientado a la integración con el
**app Android** (`android/`). El backend es un **sink opcional de "data-mule"**: el mesh funciona
sin él, nunca es dependencia dura. El app sube lo que recolecta cuando recupera conectividad.

> Fuente de verdad del código: `backend/src/`. Si cambias contratos, actualiza este doc.
> Contrato compartido de tipos/cripto: `packages/shared/src/` (`@guacamaya/shared`).

---

## 1. Configuración base

| Concepto | Valor |
|---|---|
| Base URL (dev, emulador Android) | `http://10.0.2.2:3000` (loopback del host) |
| Base URL (dispositivo físico) | `http://<IP-LAN-del-PC>:3000` |
| Base URL (prod) | el dominio HTTPS desplegado |
| Content-Type (JSON) | `application/json` |
| Rate limit global | 100 req/min por IP |

En dev sobre HTTP en claro, Android (targetSdk 34) bloquea cleartext por defecto: el app necesita
un `network_security_config.xml` que permita el host de dev. En prod usa HTTPS y no hace falta.

---

## 2. Modelo de autenticación

Las API keys viajan en header **`X-Api-Key: <key>`** o **`Authorization: Bearer <key>`**
(comparación *timing-safe*, `backend/src/security/auth.ts`). Hay tres llaves de servidor:

| Llave (env) | Protege |
|---|---|
| `GUACAMAYA_ADMIN_KEY` | `POST /channels/:id/records` (alertas oficiales) |
| `GUACAMAYA_READ_KEY` | `GET /locations`, canales WS sensibles (cae a admin) |
| `GUACAMAYA_WS_KEY` | upgrade de WebSocket `/ws?token=` (cae a read) |

### ⚠️ Regla de oro para el app

**El app NUNCA debe llevar embebidas estas llaves de servidor** (`ADMIN`/`READ`/`WS`) ni la
`SUPABASE_SERVICE_ROLE_KEY`. Son secretos de servidor; si se compilan en el APK, se filtran.

Lo que el app **sí** usa no requiere ninguna llave:

| Endpoint que usa el app | ¿Requiere llave? |
|---|---|
| `POST /ingest` (sube frames del mesh) | ❌ No — el gate es la firma Ed25519 del frame |
| `GET /pubkey` | ❌ No (público) |
| `GET /channels`, `GET /channels/:id/records` | ❌ No (público) |
| WebSocket de canales **comunitarios** (`solicito-ayuda`, `estoy-bien`) | ❌ No |
| `POST /resolve/evidence`, `POST /resolve` | ❌ No¹ (gate = firmas de los testigos) |

¹ En prod `POST /resolve/evidence` puede exigir read key si `RESOLVE_EVIDENCE_REQUIRE_AUTH=true`.
Si se quiere que el app suba evidencia en prod, exponer un endpoint público dedicado o relajar esa
config — **no** embeber la read key.

`GET /locations` (read key) y `POST /channels/:id/records` (admin) son del **dashboard/operador**,
no del teléfono.

---

## 3. Formato del frame de subida (lo más importante para el `IngestClient`)

El frame BLE on-wire son **119 bytes**: `1 B TTL (mutable) + 22 B payload + 32 B pubkey + 64 B sig`.
Para subir a `/ingest` se **quita el primer byte (TTL)** → **118 bytes**, y se codifica en
**Base64 (sin saltos de línea)**. El backend tolera 119 B (descarta el primer byte).

```
Frame de subida (118 B):
  [0..21]    22 B  payload firmado        (big-endian)
  [22..53]   32 B  pubkey Ed25519 del origen
  [54..117]  64 B  firma Ed25519 sobre los 22 B de payload

Payload (22 B, big-endian) — proto/Payload.kt:
  [0..3]    int32   latE7   (lat × 1e7)
  [4..7]    int32   lonE7   (lon × 1e7)
  [8..11]   uint32  tsUnix  (segundos)
  [12..15]  4 B     nodeId  (= SHA-256(pubkey)[0..3])
  [16]      uint8   flags   (bit0 hasHeavy, bit1 critical, bits2-3 battery, bits4-7 hopTtl)
  [17]      uint8   sosType (0 medical,1 distress,2 food,3 water,4 shelter,5 fire,6 violence,7 other)
  [18..19]  uint16  msgId
  [20..21]  uint16  CRC16-CCITT sobre payload[0..19]
```

**La localización ES el lat/lon dentro del payload firmado** — no se envía aparte. Un frame SOS
verificado es, por sí mismo, un punto geolocalizado autenticado.

En Android, el app ya tiene `payload22 + pubkey32 + sig64` en `SosForegroundService`
(origen) y en `MessageStore`/Room (frames recibidos). El `IngestClient` solo concatena esos 118 B
y hace `Base64.encodeToString(bytes, Base64.NO_WRAP)`.

> Para re-subir frames **recibidos de otros** (mula real), `MessageEntity` debe persistir la firma
> de 64 B (hoy guarda `payloadRaw` y `pubkey` pero **no** la `sig` — ver `FloodRouter.kt`). Sin la
> firma, el backend no puede re-verificar y los rechaza. El frame **propio** no necesita Room.

---

## 4. Referencia de endpoints

### 4.1 `GET /health` — liveness
- **Auth:** no. **Respuesta:** `{ "ok": true }`.
- **Uso móvil:** comprobar conectividad antes de un flush del mule.

### 4.2 `GET /pubkey` — identidad del backend
- **Auth:** no. **Respuesta:** `{ "publicKey": "<hex 32B>" }`.
- **Uso móvil:** clave pública Ed25519 del backend para **verificar registros oficiales**
  (`verified:true`) con `verifyRecordSignature` (`@guacamaya/shared`). Cachear; cambia solo si el
  servidor rota identidad.

### 4.3 `GET /channels` — lista de canales
- **Auth:** no.
- **Respuesta:** array de `{ id, name, verifiedOnly }`.
  ```json
  [
    { "id": "alertas",        "name": "Alertas Oficiales",       "verifiedOnly": true },
    { "id": "refugios",       "name": "Refugios y Recursos",     "verifiedOnly": true },
    { "id": "ayuda-medica",   "name": "Ayuda Médica",            "verifiedOnly": true },
    { "id": "estoy-bien",     "name": "Estoy Bien (Comunidad)",  "verifiedOnly": false },
    { "id": "solicito-ayuda", "name": "Solicito Ayuda (Comunidad)", "verifiedOnly": false }
  ]
  ```

### 4.4 `GET /channels/:id/records?since=<ms>` — registros de un canal
- **Auth:** no. `since` = unix ms (default 0 = todos). Canal desconocido → **404**.
- **Respuesta:** array de `ChannelRecord` (`{ id, channel, timestamp, ttl, author, verified, payload, sig? }`).
- **Uso móvil:** *pull* de alertas oficiales (`alertas`, `refugios`, `ayuda-medica`) emitidas por el
  operador, p. ej. al recuperar red. Verificar `verified` + `sig` contra `/pubkey`.

### 4.5 `POST /channels/:id/records` — crear registro oficial *(NO para el app)*
- **Auth:** **admin** (`X-Api-Key: GUACAMAYA_ADMIN_KEY`). Rate limit 20/min.
- Solo canales oficiales (`alertas` | `refugios` | `ayuda-medica`); otro → **403**.
- **Body:** `{ "payload": { ... } }` (objeto, ≤ `MAX_OFFICIAL_PAYLOAD_BYTES` = 16 KB). Sin payload → 400.
- El backend firma (`verified:true`), persiste y lo emite por WS. Es del **dashboard/operador**.

### 4.6 `POST /ingest` — subida data-mule de frames firmados ⭐ (endpoint principal del app)
- **Auth:** **no** (el gate es la firma). Rate limit 30/min; batch ≤ `MAX_INGEST_BATCH` (200);
  cada frame base64 ≤ `MAX_FRAME_B64_LENGTH` (256 chars).
- **Body:** `{ "frames": ["<base64 del frame de 118 B>", ...] }`.
- **Zero-trust:** cada frame se re-verifica (`backend/src/mesh/frame.ts`), barato primero:
  1. CRC16-CCITT sobre payload[0..19]
  2. binding `SHA-256(pubkey)[0..3] == nodeId`
  3. Ed25519 verify sobre los 22 B de payload

  El check de timestamp/replay se **omite** aquí (una mula sube reportes viejos legítimamente).
- **Efectos por frame verificado:**
  - Crea un `ChannelRecord` comunitario en `solicito-ayuda` (`verified:false`, `id = SHA-256(payload)`).
  - Persiste un `LocationPoint` (lat/lon del payload, `deviceId = device-<pubkey origen>`).
  - Emite ambos por WebSocket.
- **Respuesta:**
  ```json
  { "success": true, "ingested": 2, "duplicate": 0, "rejected": 0, "locationsIngested": 2, "reasons": {} }
  ```
  `ingested` = nuevos; `duplicate` = ya existían (dedupe por id); `rejected` + `reasons` =
  frames que no pasaron el gate.
- **Idempotente:** re-subir el mismo frame deduplica por `id` (hash de contenido). El app puede
  reintentar sin miedo a duplicar.
- **Ejemplo:**
  ```bash
  curl -X POST $BASE/ingest -H 'Content-Type: application/json' \
    -d '{"frames":["<base64-118B>"]}'
  ```

### 4.7 `GET /locations?since=<ms>&deviceId=<id>` — historial de trayectoria *(dashboard)*
- **Auth:** **read** (`X-Api-Key: GUACAMAYA_READ_KEY`). Solo lectura.
- `deviceId` opcional, debe casar `^device-[0-9a-f]{64}$`; inválido → 400.
- **Respuesta:** array de `LocationPoint`, orden cronológico.
- **Uso:** el **dashboard de mapa/alertas offline** lo consume; el teléfono **no**. Las posiciones
  se pueblan exclusivamente desde `/ingest` (no hay ingesta de ubicación por JSON confiable).

### 4.8 `POST /resolve/evidence` — subir imagen de evidencia
- **Auth:** en prod, read key si `RESOLVE_EVIDENCE_REQUIRE_AUTH=true` (default). Rate limit 30/min.
- **Body:** bytes crudos de la imagen, `Content-Type: application/octet-stream` (≤ 8 MiB).
- **Respuesta:** `{ imageHash, storageKey, uploadToken, expiresInMs }`.
  El `uploadToken` (HMAC) liga esa imagen a una ventana corta (~5 min) y se entrega luego por
  testigo en `POST /resolve`. Dev: la imagen va a disco (`EVIDENCE_DIR`); prod: a Supabase Storage
  (bucket `SUPABASE_EVIDENCE_BUCKET`, debe existir).

### 4.9 `POST /resolve` — envelope de testigos (disarm co-firmado de un SOS)
- **Auth:** no (gate = firmas Ed25519 de los testigos). Rate limit por `deviceId` del submitter.
- **Body:** `ResolveEnvelope`:
  ```json
  {
    "targetSosId": "<hex64: ChannelRecord.id del SOS>",
    "targetSosAuthor": "device-<hex64 del originador>",
    "submittedAt": 1782645600000,
    "note": "finder on scene",
    "witnesses": [
      {
        "deviceId": "device-<hex64>",
        "pubkey": "<hex64>",
        "lat": 14.6350, "lon": -90.5070,
        "ts": 1782645600000,
        "imageHash": "<hex64 = sha256 de la imagen>",
        "uploadToken": "<de /resolve/evidence>",
        "sig": "<hex128>"
      }
    ]
  }
  ```
- **Firma de cada testigo** (`@guacamaya/shared` → `signWitness`): cada testigo firma con su propia
  clave privada `sha256(witnessMessageBytes(envelope, witness))`, donde el mensaje canónico es
  (byte-estable, `\n` como separador):
  ```
  "guacamaya.resolve.v1\n" + targetSosId + "\n" + targetSosAuthor + "\n" +
  submittedAt + "\n" + (note ?? "") + "\n" +
  deviceId + "\n" + lat.toFixed(7) + "\n" + lon.toFixed(7) + "\n" +
  ts + "\n" + imageHash + "\n" + (macObservationHashes?.join(",") ?? "") + "\n"
  ```
  El app móvil debe reproducir **exactamente** este formato en Kotlin para que la firma verifique.
- **Cascada anti-troll** (cheapest first): shape 400 → target existe (404 `target_unknown`) →
  recencia ≤ 72 h (410 `target_stale`) → autor coincide → rate-limit → testigo ≠ originador →
  uno-por-target → geo ≤ `RESOLVE_GEO_RADIUS_KM` (5 km) → firma válida → uploadToken válido →
  quórum M-de-N (`RESOLVE_QUORUM_REQUIRED`).
- **Respuesta:** `{ accepted, status, targetSosId, quorumNeeded, quorumSeen, receiptId?, reasons }`.
  `accepted:false` con `reason` cuando ningún testigo nuevo pasa.

### 4.10 WebSocket `/ws` — actualizaciones en vivo
- **URL:** `ws://<host>/ws` (+ `?token=<wsKey>` para canales sensibles). El app solo necesita token
  para canales **sensibles** (`locations`, `alertas`, `refugios`, `ayuda-medica`, `resuelto`,
  `resolves`); los **comunitarios** (`solicito-ayuda`, `estoy-bien`) no requieren token.
- **Suscribir:** enviar `{"type":"subscribe","channel":"solicito-ayuda"}` → respuesta
  `{"type":"subscribed","channel":"solicito-ayuda"}`.
- **Eventos del servidor:**
  - `{"type":"record","data":<ChannelRecord>}`
  - `{"type":"location","data":<LocationPoint>}`
  - `{"type":"resolve","data":<ResolveReceipt>}`
- **Uso móvil:** recibir SOS comunitarios en vivo cuando hay red, sin *polling*.

---

## 5. Errores y límites

| Código | Significado |
|---|---|
| 400 | body/validación inválida (frames no-array, payload faltante, deviceId mal formado, batch > 200) |
| 401 | falta o no coincide la API key requerida |
| 403 | canal no permitido para registro oficial |
| 404 | canal o target desconocido |
| 410 | target SOS demasiado viejo (resolve) |
| 413 | imagen de evidencia > 8 MiB |
| 429 | rate limit excedido |
| 503 | servidor mal configurado (falta la key esperada) |

Rate limits: global 100/min; `/ingest` y `/resolve*` 30/min; escritura oficial 20/min.

---

## 6. Flujo recomendado de integración móvil

```
                 ┌──────────────── App Android (org.sosnet) ────────────────┐
  Usuario        │  Broadcast SOS → SosForegroundService.startBroadcasting()  │
  toca SOS  ───► │     Payload(latE7,lonE7,ts,nodeId,flags,sosType,msgId)     │
                 │     + Ed25519 sign → emite por BLE (119 B)                 │
                 │                                                            │
  Mesh BLE       │  FloodRouter.onFrame() → cascada → Room (MessageEntity)    │
  (sin internet) │     [persistir también sig64 para poder mular]            │
                 │                                                            │
  Recupera red   │  IngestClient.flush():                                     │
                 │     frames118 = payload22 + pubkey32 + sig64               │
                 │     Base64.NO_WRAP  →  POST /ingest {"frames":[...]}        │
                 └───────────────────────────┬────────────────────────────────┘
                                             │ HTTP (best-effort, no bloquea el mesh)
                                             ▼
                          POST /ingest  (zero-trust: CRC→binding→Ed25519)
                                             │
                          ChannelRecord(solicito-ayuda) + LocationPoint
                                             ▼
                              Supabase: channel_records / location_points
                                             ▼
                         GET /locations (read key) → Dashboard de alertas offline
```

Pautas:
- **Best-effort:** toda subida es en background, con try/catch; un fallo de red **nunca** debe
  bloquear ni romper el mesh (el app es serverless por diseño).
- **Reintento seguro:** `/ingest` deduplica por `id`; reintentar es idempotente. Un flag `synced`
  en Room evita re-subir lo ya confirmado.
- **Disparadores del flush:** al emitir el SOS propio (si hay red) y al detectar red disponible
  (`ConnectivityManager.NetworkCallback`).
- **Base URL configurable:** `BuildConfig.BACKEND_BASE_URL` (emulador `http://10.0.2.2:3000`).
- **Sin secretos en el APK:** el app no porta llaves de servidor (ver §2).

> Estado actual: el `IngestClient` en Android **aún no existe** (trabajo abierto, ver
> `android/CLAUDE.md`). El lado backend está completo y verificado end-to-end contra Supabase.
