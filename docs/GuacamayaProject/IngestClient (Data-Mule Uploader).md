# IngestClient (Data-Mule Uploader)

El **data-mule de subida** de [[Guacamaya (Android)]]: junta los frames de malla
verificados que el teléfono recogió y los sube al `POST /ingest` del
[[Backend Data-Mule]] cuando recupera Wi-Fi/LTE. Cierra el pendiente histórico "no
existe uploader Kotlin". Parte de [[GuacamayaProject]].

> **Estado:** implementado en `develop` (commit `eee3508`), build + tests JVM en
> verde. **Pata de aceptación del backend verificada** contra un server real (2026-06-28,
> ver abajo); **pata en dispositivo** (app → BLE → WorkManager → POST) aún sin smoke en
> hardware.

Paquete: `net.guacamaya.ingest`.

## El bloqueador que hubo que resolver primero: la firma no se guardaba

`FloodRouter` recibía `sig64` pero `MessageEntity` **no la persistía** (no había
columna `sig`), así que el frame de 118 B de `/ingest` no se podía reconstruir.
Solución: migración Room **no destructiva** `Migration(2,3)` que añade:

- `sig` (BLOB) — los 64 B de firma Ed25519. `FloodRouter` ahora la escribe.
- `uploaded` (INTEGER) — flag de "ya aceptado por el backend".

DB version 2→3. Se conservó `fallbackToDestructiveMigration()` solo como red de
seguridad para saltos no contemplados. Las filas pre-v3 quedan con `sig` vacía y
se **excluyen** de la subida (filtro `length(sig) = 64`). Detalle del schema en
[[Guacamaya (Android)]]; el frame de 118 B en [[Protocolo y Frame]].

## Componentes

| Clase | Rol |
|---|---|
| `IngestFrame` | Reconstruye el frame canónico de 118 B (`payload‖pubkey‖sig`) y lo codifica en base64. Usa `java.util.Base64` (sirve en minSdk 26 **y** en tests JVM). Puro. |
| `IngestApi` / `IngestClient` | `HttpURLConnection` `POST {baseUrl}/ingest`. **Sin API key** — el endpoint es zero-trust por firma, re-verificado en el servidor. `IngestApi` es interfaz → el repo se testea con un fake. |
| `IngestRepository` | El bucle de subida: lee frames no subidos (más viejos primero), arma el batch (≤200), POST, marca `uploaded`. Puro de deps Android → unit-testeado. |
| `IngestUploadWorker` | `CoroutineWorker` con constraint `NetworkType.CONNECTED`, backoff exponencial, unique work. El envoltorio Android. |

## Decisiones de diseño (revisadas y fijadas)

1. **Migración real** `Migration(2,3)`, no destructiva (preservar frames recogidos en campo).
2. **`HttpURLConnection`**, no OkHttp/Retrofit — cero deps nuevas de red (acorde al recorte de osmdroid/RAM gama baja).
3. **`NetworkType.CONNECTED`** (Wi-Fi o LTE), no solo Wi-Fi — en un desastre la recuperación por LTE es justo el caso.
4. **URL del backend** vía `BuildConfig.INGEST_BASE_URL`, default `http://10.0.2.2:3000` (loopback del host del emulador).

## El contrato de respuesta condiciona el bookkeeping

`POST /ingest` devuelve **solo contadores agregados** (`{ success, ingested,
duplicate, rejected, locationsIngested, reasons }`), **no** resultado por frame. Como
solo subimos frames que ya pasaron nuestra cascada de rechazo **idéntica** local,
vuelven como `ingested` o `duplicate` (ambos = el backend ya los tiene); `rejected`
es prácticamente imposible. Regla: **marcar el batch entero `uploaded` ante cualquier
HTTP 2xx**; si `rejected > 0`, loguearlo fuerte (es un **canario de desincronización
de wire-format** — ver el riesgo de 3 vías en [[Protocolo y Frame]]). Ante fallo de
transporte (`IOException`/no-2xx) → `RETRY`, los batches ya marcados se conservan y el
reintento continúa donde quedó.

## Cómo se dispara

`GuacamayaForegroundService` encola el worker en cada `onStartCommand` (Broadcast /
Observe). Es **no-op hasta que haya conectividad** (WorkManager espera el constraint),
y es *unique work* (`KEEP`) así que disparos repetidos no se apilan.

## Red en claro (cleartext) para la demo

`10.0.2.2:3000` es HTTP en claro, que Android bloquea por defecto desde API 28. Se
añadió `res/xml/network_security_config.xml` que permite cleartext **solo** a
`10.0.2.2` / `localhost` / `127.0.0.1`; todo lo demás sigue HTTPS-only. Cambiar a
HTTPS para cualquier despliegue real.

## Verificación contra backend real (2026-06-28)

Verificada la **pata de aceptación del backend** — que un frame con el layout exacto de
`IngestFrame` (118 B, `payload@0 ‖ pubkey@22 ‖ sig@54`) pasa la cascada zero-trust. El orden
de bytes coincide en las tres fuentes: `IngestFrame.encode` (Kotlin) == `gen-postman-frames.ts`
== offsets de `frame.ts`. Contra `bun run dev:backend` (store en memoria, llaves efímeras):

| Caso | Resultado |
|---|---|
| Longitud del frame generado | **118 B** = `IngestFrame.SIZE` ✅ |
| POST 2 frames válidos | `ingested:2, locationsIngested:2, rejected:0` ✅ |
| Re-POST de los mismos (dedup) | `duplicate:2` ✅ |
| Byte de firma alterado | `rejected:1, reasons:{"signature invalid":1}` ✅ |
| `GET /locations` sin key | **401** (hardening OK) ✅ |
| `GET /locations` con read key | 2 puntos derivados del frame, coords correctas, `deviceId = device-<pubkey origen>` ✅ |

Esto prueba toda la cadena de la que depende el uploader: CRC → binding de pubkey → verify
Ed25519 → `ChannelRecord` + `LocationPoint`, con dedup y rechazo de manipulación. El riesgo de
sync de wire-format de 3 vías está **en sync** hoy.

## Pendiente / siguiente

- [ ] **Smoke de la pata en dispositivo** (no cubierto arriba): app en **emulador** o dos
      teléfonos → Observe recoge un frame BLE real → Room persiste `sig` → `IngestUploadWorker`
      dispara al recuperar red → `HttpURLConnection` POST a `10.0.2.2:3000`. Hoy solo cubierto por
      tests JVM (`IngestFrameTest`, `IngestRepositoryTest`); un emulador sin BLE no recoge frames.
- [ ] **Tinte de "rejected"**: hoy se marca `uploaded` aunque el backend rechace (para
      no reintentar infinito). Si en campo aparecieran rechazos reales, distinguir un
      estado terminal `rejected` para no perder el frame en silencio.
- [ ] **URL configurable en runtime** (hoy es `BuildConfig`, fijo por build) — setting
      o descubrimiento para apuntar a un backend de LAN distinto del emulador.
- [ ] **`gradle.properties` fija `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk`**
      (config del equipo); en máquinas sin ese JDK exacto el build falla — se sorteó con
      `-Dorg.gradle.java.home=...`. Ver [[Build y Entorno]].

Contrato del endpoint y verificación zero-trust: [[Backend Data-Mule]]. Estado global:
[[Estado y Pendientes]].
