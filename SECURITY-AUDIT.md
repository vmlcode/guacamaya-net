# Auditoría de Seguridad — GuacaMalla Net

**Fecha:** 2026-07-01
**Alcance:** backend (Bun + Fastify + WebSocket), Android (Kotlin + Compose mesh), shared contracts, CI/CD, git history
**Branch auditada:** `feat/background-survival` (commit `827456a`)
**Propósito:** insumo para discusión con el equipo — clasificar cada hallazgo como **intencional** (decisión de diseño), **error** (bug a corregir), o **riesgo aceptado** (consciente, sin acción inmediata).

## Estado de implementación (este PR)

Los siguientes hallazgos se abordan en este PR. Los marcados `DECISIÓN` quedan abiertos a discusión del team.

| # | Hallazgo | Estado | Acción del PR |
|---|----------|--------|---------------|
| H1 | Dockerfile root | **FIX** | `USER bun` + `--chown=bun:bun` en `Dockerfile` |
| H2 | CORS wildcard default | **FIX** | `config.ts` refuse start en prod sin `CORS_ORIGINS` |
| H3 | google-services.json tracked | **DOC** | `.gitignore` ampliado; `git rm --cached` + rotación queda como follow-up (ver §H3) |
| H4 | WS subscription flood | **FIX** | Cap `WS_MAX_SUBSCRIPTIONS_PER_CLIENT` (default 16) en `ws/server.ts` |
| H5 | Body limit mismatch | **FIX** | `bodyLimit` route-level en `/resolve/evidence` |
| H6 | Android intent actions exportados | **FIX** | Intent-filter quitado de `MainActivity` release (debug ya cubre vía `AdbCommandReceiver`) |
| H7 | DB sin encriptar | **DECISIÓN** | Sin código change — team define threat model |
| H8 | Keystore sin StrongBox | **FIX** | `setIsStrongBoxBacked(true)` con fallback a TEE en `Identity.kt` |
| M1 | XOR manual vs `timingSafeEqual` | **FIX** | `crypto.timingSafeEqual` en `evidence.ts` |
| M2 | WS sin Origin check | **FIX** | `originAllowed()` valida contra allowlist cuando header presente |
| M3 | Stores in-memory ilimitados | **DOC** | Documentado como dev-only |
| M4 | Dev keys logueados | **FIX** | Gate por `stdout.isTTY` + `CI === undefined` |
| M5 | Auto-disarm cooldown | **DECISIÓN** | Sin cambio de defaults — team revisa parámetros |
| M6 | WakeLock sin timeout | **FIX** | `acquire(4h)` safety timeout en `GuacamayaForegroundService.kt` |
| M7 | BootReceiver silencioso | **DOC** | Foreground service notification ya cubre — sin change |
| M8 | Sin TLS pinning | **DOC** | Modelo zero-trust hace pinning defensa en profundidad |
| M9 | CI sin SHA pinning | **FIX** | Actions pinned por SHA + `permissions: contents: read` + cleanup `sa.json` en `android-distribute.yml` |
| M10 | `resolve_receipts` crece | **FIX** | `deleteTerminalReceiptsOlderThan` en `resolvesRepo.ts` + invoca cada 60 sweeps en `index.ts` |

**Net:** 12 fixes aplicados, 3 documentados para decidir (H7, M5, M7), 3 documentados como riesgo aceptable (H3 follow-up, M3, M8).

---

## Resumen ejecutivo

| Severidad | Cantidad | Acción sugerida |
|-----------|----------|-----------------|
| HIGH      | 8        | Decidir en próxima sync del team |
| MEDIUM    | 10       | Triage por owner del módulo |
| LOW       | 9        | Backlog de hardening |
| **Total** | **27**   | — |
| Intencional (confirmado) | 8 | Documentar y cerrar |

**Línea general:** el diseño criptográfico central del mesh (Ed25519 por frame, cascade cheapest-first, zero-trust en `/ingest`, dedupe por hash) **es sólido y consistente** entre backend y Android. Los hallazgos se concentran en:

1. **Hardening operacional del backend** (Dockerfile, CORS, body limits).
2. **Protección de superficies Android exportadas** (intent actions, DB en plaintext).
3. **Higiene de secrets/git** (`google-services.json` trackeado, CI sin SHA pinning).

No se encontraron vulnerabilidades que rompan el modelo zero-trust de ingesta de frames ni el cascade de verificación.

---

## Metodología

- **Pase 1:** tres agentes Explore en paralelo (backend / Android / shared + secrets + git).
- **Pase 2:** verificación manual archivo por archivo de cada hallazgo, descartando falsos positivos comunes:
  - Algunos agentes afirmaron "POST /channels/:id/records sin auth" — **falso**: requiere `X-Api-Key` via `requireApiKey(securityConfig.adminApiKey, ...)` (`backend/src/channels/routes.ts:50`).
  - Algunos afirmaron "GET /locations sin auth" — **falso**: requiere read key (`locations/routes.ts:10`).
  - Algunos afirmaron "WS upgrade sin auth" — **parcialmente cierto por diseño** (comentario explícito en `ws/server.ts:48-54`): clientes sin llave acceden a canales públicos; canales sensibles (`locations`, `alertas`, `resuelto`, …) requieren auth al subscribe.
- **Pase 3:** lectura de `schema.sql` (RLS habilitado en las 4 tablas), `Identity.kt` (confirma configuración Keystore), `validation.ts` (validadores robustos).

---

## Hallazgos HIGH

### [H1] Dockerfile corre como root

- **Ubicación:** `Dockerfile:1-33`
- **Síntoma:** Imagen base `FROM oven/bun:1` sin directive `USER`. El proceso Fastify corre como `uid=0` dentro del contenedor.
- **Impacto:** Cualquier RCE en el proceso backend → compromiso total del contenedor (escritura en filesystem host-virtualizado, acceso a cualquier secret montado, escalation a capabilities del container runtime).
- **¿Intencional o error?** Hipótesis: **error**. No hay comentario justificando root. Puerto :3000 > 1024, no requiere privileged binding.
- **Pregunta para el team:** ¿Hay razón para root (debug en prod, bind a puerto < 1024, escritura en volume montado)? Si no → agregar `USER bun` y `--chown` en COPY.

### [H2] CORS wildcard por defecto en producción

- **Ubicación:** `backend/src/security/config.ts:3-7,27-30`
- **Síntoma:** `parseOrigins(undefined)` retorna `true` (wildcard en Fastify CORS). Sin `CORS_ORIGINS` seteado, el servidor responde `Access-Control-Allow-Origin: *` incluso con `NODE_ENV=production`.
- **Impacto:** Cualquier sitio web del browser del usuario puede hacer requests cross-origin a la API. Combinado con WS sin Origin check (M2), amplía superficie de abuso. **No filtra credenciales** por sí mismo (CORS no es auth), pero relaja laschutz natural del navegador.
- **¿Intencional o error?** **Error**. En `production` debería requerirse `CORS_ORIGINS` explícito, igual que ya se exige `GUACAMAYA_ADMIN_KEY` (`config.ts:81-83`).
- **Pregunta para el team:** ¿Confirmar que la dashboard vive en otro origin? Listar origins permitidos.

### [H3] `google-services.json` trackeado en git

- **Ubicación:** `android/app/google-services.json` (commit `c500d13077f175759ed374c205ac50d541dc1f35`)
- **Síntoma:** Archivo en repo. Contiene:
  - `project_number: 800641010595`
  - `project_id: guacamalla-app`
  - `api_key: AIzaSyAjgHuFOT97JMnINSc1kbKazMxXjgaW05g`
  - `mobilesdk_app_id: 1:800641010595:android:cb83f00966ab0e87faa816`
- **Impacto:** **Limitado en la práctica**. Las Firebase API keys en `google-services.json` son client-side por diseño, restringidas por Google Cloud a clientes Android del package `net.guacamaya`. No son service-account keys. Aun así:
  - Filtra el project ID y permite intentos de abuso de quotas no autenticadas (Crashlytics, FCM).
  - **No debe** confundirse con `firebase-adminsdk-*.json` (service account) — ése sí sería crítico.
- **¿Intencional o error?** **Debatible**. Algunos equipos lo trackean intencionalmente (es el default de Firebase); otros lo `.gitignore` por higiene. **No es urgente rotar la key** mientras las restricciones de Google Cloud estén activas.
- **Pregunta para el team:** ¿Política de proyecto? Si se `.gitignore`, considerar `git filter-repo` para borrar historia + rotar API key en Firebase Console.

### [H4] WebSocket subscription flood — Set sin límite

- **Ubicación:** `backend/src/ws/server.ts:70-76`
- **Síntoma:** `client.channels.add(data.channel)` sin tope. Un cliente (incluso no autenticado) puede hacer `subscribe` a miles de canales arbitrarios.
- **Impacto:** Memory growth por cliente; `broadcastRecord` itera `clients` y revisa `client.channels.has(...)` — costo O(clientes × canales_por_cliente). DoS de memoria/CPU.
- **¿Intencional o error?** **Error**. No hay razón para que un cliente se suscriba a >32 canales. Sugerencia: cap por cliente + reject con error 408/429.
- **Pregunta para el team:** ¿Cuál es el máximo razonable? `MAX_WS_SUBSCRIPTIONS_PER_CLIENT=16`?

### [H5] Body limit mismatch en `/resolve/evidence`

- **Ubicación:** `backend/src/index.ts:16` (Fastify default), `backend/src/resolve/routes.ts:131-133`
- **Síntoma:** Fastify se instancia con `Fastify({ logger: true })` — sin `bodyLimit` → default **1 MB** global. La ruta `/resolve/evidence` valida `body.length > securityConfig.resolve.maxImageBytes` (default **8 MB**). Resultado: evidencia > 1 MB rechazada por Fastify antes de llegar al handler, con error genérico `FST_ERR_CTP_BODY_TOO_LARGE`.
- **Impacto:** **Bug funcional**: el flujo de evidencia está roto para imágenes > 1 MB. No es vulnerability directa, pero fuerza a los testigos a comprimir imágenes (pierde valor forense).
- **¿Intencional o error?** **Error**. Falta `bodyLimit` route-level: `{ bodyLimit: 8 * 1024 * 1024 + 256 }` o similar.
- **Pregunta para el team:** Confirmar 8 MB; en su defecto, subir global a `bodyLimit: 9 * 1024 * 1024`.

### [H6] Android — 6 intent actions exportados sin protección

- **Ubicación:** `android/app/src/main/AndroidManifest.xml:72-80`
- **Síntoma:** `MainActivity` exporta intent-filter con acciones `net.guacamaya.action.{OBSERVE_ON,OBSERVE_OFF,START,STOP,HEARTBEAT_ON,HEARTBEAT_OFF}` sin `android:permission` signature-level. Cualquier app local puede:
  ```bash
  adb shell am start -a net.guacamaya.action.START -n net.guacamaya/.ui.MainActivity
  ```
  o desde otra app vía `Intent("net.guacamaya.action.OBSERVE_ON").addCategory(DEFAULT)`.
- **Impacto:** App maliciosa local puede alternar el modo mesh del usuario sin su consentimiento (prender observing, apagar SOS, disparar heartbeat). No expone secrets pero permite sabotaje discreto.
- **¿Intencional o error?** Hipótesis: **intencional para demo via adb** (el comentario en `AndroidManifest.xml:71` lo sugiere: "adb demo"). Pero queda activo en build de release.
- **Pregunta para el team:** ¿Estos actions deben existir en release? Si sólo son para debug/demo, mover a `src/debug/AndroidManifest.xml` (ya existe el directorio). Si se necesitan en release, proteger con `<permission android:protectionLevel="signature">`.

### [H7] Android — `guacamaya.db` sin encriptar

- **Ubicación:** `android/app/src/main/kotlin/net/guacamaya/data/MessageStore.kt:158-177`
- **Síntoma:** Room database `guacamaya.db` en almacenamiento plano. Location history, SOS records, y metadata de testigos legibles por cualquier app con acceso al filesystem (si device rooteada o via backup ADB si `allowBackup` estuviera true — está en `false`, mitiga un vector).
- **Impacto:** En dispositivo rooteada o robada con ADB habilitado, historial de ubicaciones de víctimas y autores de SOS es legible. Esto **contrasta** con el cipher del Keystore (Identity.kt), que sí protege la llave privada.
- **¿Intencional o error?** **Discutible**. SQLCipher añade dependencia + overhead; el equipo puede haber aceptado el riesgo. Pero dado que la app maneja ubicaciones de personas en peligro, merece reconsideración.
- **Pregunta para el team:** ¿Acceptable risk? Alternativa: [androidx sqlite-cipher](https://github.com/sqlcipher/sqlcipher) o [Requery SQLite encrypted](https://github.com/requery/requery).

### [H8] Android — Keystore sin `setUserAuthenticationRequired`

- **Ubicación:** `android/app/src/main/kotlin/net/guacamaya/crypto/Identity.kt:103-119`
- **Síntoma:** `ensureMasterKey()` crea AES-256-GCM key con:
  - `setKeySize(256)` ✓
  - `setRandomizedEncryptionRequired(true)` ✓
  - `setBlockModes(GCM)`, `setEncryptionPaddings(NONE)` ✓
  - **NO** `setUserAuthenticationRequired(true)`
  - **NO** `setIsStrongBoxBacked(true)` (hardware Keymaster)
  - **NO** `setInvalidatedByBiometricEnrollment(true)`
- **Impacto:** En dispositivo rooteada, extracción de la master key del Keystore → desencriptar el seed Ed25519 → suplantar identidad del nodo en el mesh (firmar SOS falsos, silenciar reales via resolve-flow).
- **¿Intencional o error?** Probable **intencional por UX**: el mesh debe funcionar en background sin desbloqueo biométrico. `setUserAuthenticationRequired(true)` rompería el foreground service. Pero StrongBox sí podría usarse sin UX impact.
- **Pregunta para el team:** ¿Aceptar y documentar, o añadir `setIsStrongBoxBacked(true)` con fallback a TEE? Revisar threat model en `android/docs/crypto.md`.

---

## Hallazgos MEDIUM

### [M1] `verifyUploadToken` con XOR manual en vez de `timingSafeEqual`

- **Ubicación:** `backend/src/resolve/evidence.ts:56-62`
- **Síntoma:** Comentario dice "Constant-time-ish compare via length-prefixed buffer equality" pero usa `token.charCodeAt(i) ^ expected.charCodeAt(i)` manual. El circuito de optimización de V8 puede romper constant-time-ness.
- **Impacto:** Bajo. HMAC-SHA256 robusto, timing attack impracticable de todas formas. Pero `auth.ts:19` sí usa `crypto.timingSafeEqual` para API keys — inconsistencia.
- **¿Intencional o error?** **Error** de consistencia. Fix trivial: `timingSafeEqual(Buffer.from(token), Buffer.from(expected))`.

### [M2] WebSocket no valida header `Origin`

- **Ubicación:** `backend/src/ws/server.ts:44-60`
- **Síntoma:** `server.on("upgrade", ...)` no revisa `request.headers.origin`. Cualquier sitio web puede abrir WS al backend desde el browser del usuario (CSWSH — Cross-Site WebSocket Hijacking). Para canales públicos no auth, esto permite a un sitio malicioso leer datos del stream en nombre del usuario.
- **Impacto:** Medio. No filtra secrets, pero expone el stream de records/locations/resolves a scripts cross-origin en el navegador de un usuario autenticado (si la dashboard usa cookies — no parece el caso, pero futuro).
- **¿Intencional o error?** Probable **oversight**. El equipo intencionalmente dejó upgrade abierto para clientes sin llave, pero validar `Origin` no rompe eso.
- **Pregunta para el team:** Validar `Origin` contra allowlist cuando el header está presente (vacío / ausente → permitir para clientes no-browser).

### [M3] Stores in-memory sin retención cap

- **Ubicación:** `backend/src/channels/store.ts`, `backend/src/locations/store.ts`
- **Síntoma:** Cuando Supabase no está configurado, los repos caen a `Map` en memoria sin TTL ni size cap. Crecen indefinidamente.
- **Impacto:** DoS de memoria en instancias dev/test o si Supabase se cae y la app sigue sirviendo con fallback. En prod con Supabase, no aplica.
- **¿Intencional o error?** Aceptable para dev, **documentar**.

### [M4] Dev keys efímeros logueados a consola

- **Ubicación:** `backend/src/security/config.ts:21-23`
- **Síntoma:** Si `GUACAMAYA_ADMIN_KEY` (o `READ_KEY`, `WS_KEY`) unset en dev, se genera key efímera y se loguea con `console.warn`. En contenedores dev con logs centralizados, esto puede persistir la key efímera.
- **Impacto:** Bajo (dev only, key no persiste entre reinicios). Pero en CI con `NODE_ENV != production`, la key puede ir a logs públicos.
- **¿Intencional o error?** **Intencional** (facilita onboarding dev), pero debería gatearse por `process.stdout.isTTY` o similar para no loguear en CI.

### [M5] Auto-disarm via cooldown de 15 min

- **Ubicación:** `backend/src/resolve/routes.ts:272-291`, `backend/src/index.ts:64-73`
- **Síntoma:** Cuando un SOS acumula quórum de testigos (default 2 de 3), el backend arranca un cooldown de 15 min. Si nadie dispute, `resolvesRepo.getExpiredPendingClears(Date.now())` lo promueve a `cleared` → broadcast de record `resuelto` → el SOS se considera desarmado.
- **Impacto:** Atacante que controle 2+ llaves Ed25519 (fácil de generar) + geo-spoofing dentro de 5 km del target → silencia un SOS real en 15 min sin intervención del originador. El veto por re-broadcast del originador (`checkOriginatorVeto` en `channels/routes.ts:159-168`) mitiga, pero requiere que la víctima re-emita.
- **¿Intencional o error?** **Diseño intencional** (anti-troll: sin cooldown, la víctima queda silenciada esperando su propia confirmación). Pero el umbral de 2/3 testigos es bajo; geo-radius de 5 km es amplio; cooldown de 15 min es corto.
- **Pregunta para el team:** Revisar parámetros: `RESOLVE_QUORUM_REQUIRED=3`, `RESOLVE_GEO_RADIUS_KM=1`, `RESOLVE_COOLDOWN_MIN=60`? Documentar el trade-off.

### [M6] Android WakeLock sin timeout

- **Ubicación:** `android/app/src/main/kotlin/net/guacamaya/service/GuacamayaForegroundService.kt:73,156-165`
- **Síntoma:** `PARTIAL_WAKE_LOCK` adquirido mientras `wantObserving == true`, liberado en `stopObserving()` o `onDestroy()`. Si el service crashea entre acquire y release, el wake lock persiste hasta que el proceso muere (OS lo reclaima eventualmente).
- **Impacto:** Drain de batería en edge cases. El OS reclaima en process death, pero la ventana es real.
- **¿Intencional o error?** **Error menor**. Android ProTip: siempre adquirir con timeout (`acquire(timeoutMs)`) y re-armar via Handler.

### [M7] Android — `BootReceiver` auto-start sin notificación

- **Ubicación:** `android/app/src/main/kotlin/net/guacamaya/boot/BootReceiver.kt:28-44`
- **Síntoma:** Tras `BOOT_COMPLETED`, si el último modo recordado no era `OFF`, el mesh auto-rearranca sin notificar al usuario.
- **Impacto:** Batería drenada sin contexto. El usuario puede no recordar que dejó el mesh activo. Foreground service notification aparece, pero el auto-start mismo es silencioso.
- **¿Intencional o error?** **Discutible**. Es el punto del branch `feat/background-survival` (sobrevivir reboot). Pero UX debería avisar: "Mesh auto-reiniciado después del boot".
- **Pregunta para el team:** ¿Notificación separada en boot, o confiar en la foreground-service notification?

### [M8] Android — `IngestClient` sin TLS pinning

- **Ubicación:** `android/app/src/main/kotlin/net/guacamaya/net/IngestClient.kt:44-84`
- **Síntoma:** Cliente HTTP casero con `HttpURLConnection`. Confía en el CA store del sistema. Sin SNI pinning ni certificate transparency.
- **Impacto:** Si el dispositivo tiene CA rogue instalada (enterprise MITM, malware), el tráfico al backend es interceptable. Como el cuerpo del POST ya son frames firmados Ed25519 (zero-trust persiste), el atacante sólo puede observar metadatos IP/cantidad — no puede inyectar frames falsos.
- **¿Intencional o error?** Probable **aceptable** dado el modelo zero-trust. Pero pinning añadiría defensa en profundencia.

### [M9] CI actions no fijados por SHA

- **Ubicación:** `.github/workflows/android-distribute.yml` y otros
- **Síntoma:** `actions/checkout@v4`, `actions/setup-java@v4`, etc. — pinned por tag, no por SHA. Si un tag es movido maliciosamente, la action ejecuta código atacante en el runner con acceso a secrets.
- **Impacto:** Supply-chain. GitHub recomienda SHA-pinning desde 2023.
- **¿Intencional o error?** **Error** de hardening estándar. Herramientas: `dependabot.yml` con `actions` ecosystem, o `StepSecurity Harden Runner`.

### [M10] `resolve_receipts` crece indefinidamente

- **Ubicación:** `backend/supabase/schema.sql:59-84`, `backend/src/index.ts:64-73`
- **Síntoma:** El sweep cada 60 s promueve receipts vencidos a `cleared`, pero **no los elimina**. Tampoco hay cleanup de receipts `disputed` o `rejected`. La tabla crece monótonamente.
- **Impacto:** Crecimiento de storage en Supabase. No afecta correctness (queries filtran por status), pero costos y performance a largo plazo.
- **¿Intencional o error?** Probable **oversight**. Sugerencia: cron Supabase o endpoint admin que borre receipts con `created_at < now() - 90 days` y status in (cleared, rejected).

---

## Hallazgos LOW

- **[L1] `EVIDENCE_DIR` relativo a CWD** (`config.ts:68`): default `.evidence`. Si env controlado por atacante con valor `../../etc/...`, path traversal posible. **Mitigación:** env es trusted. Aun así, validar con `path.resolve()` + chequeo de prefijo.
- **[L2] `/health` público** (`index.ts:36`): retorna `{ ok: true }`. Filtra uptime/availability a reconnaissance. Trivial pero real.
- **[L3] `getLocationId` con `accuracy` opcional** (`packages/shared/src/crypto.ts`): si el mismo punto se ingiere con y sin accuracy, generan IDs distintos → no dedup. Bug menor.
- **[L4] `FloodRouter.kt:122`** loguea `nodeId` y `msgId` hex. No sensitive por sí, pero fingerprinting pasivo en logcat.
- **[L5] `MessageStore.kt:173` `fallbackToDestructiveMigration`**: si esquema baja de versión, Room borra la DB. Riesgo de pérdida de datos si migración incorrecta.
- **[L6] Branch `init-sosnet` en remote**: branding histórico. Código no auditado. Si se conserva por histórico, marcar archived.
- **[L7] Emails personales en git history**: `ielijose@gmail.com`, `jaimestanislav@gmail.com`, etc. como autores de commits. PII menor. Squash + amend si el repo es público.
- **[L8] `/ingest` acepta batch vacío** (`validateIngestBatch` no exige `length >= 1`): gasto CPU mínimo, pero response `{success:true, ingested:0}` confuso para clientes. Validación cosmética.
- **[L9] `parseSinceParam` sin upper bound** (`validation.ts:37-41`): `Number.MAX_SAFE_INTEGER` aceptado. Permite query "all data" — rate-limitado, pero espectro amplio.

---

## Decisiones de diseño intencionales (NO vulnerabilidades)

Documentadas aquí para evitar re-debate en sync:

1. **`/ingest` omite skew temporal** (`mesh/frame.ts:28-31`) — los data mules suben reportes stale legítimamente.
2. **`/ingest` dedupe por `id = SHA-256(payload)`** — múltiples mules pueden re-subir el mismo frame; idempotente.
3. **`/ingest` sin replay protection** — consecuencia del punto 1.
4. **WS upgrade abierto** para clientes sin llave (`ws/server.ts:48-54`) — teléfonos con `LiveSosClient` no cargan key; auth se aplica al `subscribe` de canales sensibles.
5. **GPS coarse ~1 km en canales públicos** (`channels/sanitize.ts:23-25,38-50`) — `Math.round(v * 100) / 100`. Defensa de privacidad.
6. **Cascade cheapest-first en verify** — CRC16 → pubkey bind → Ed25519, idéntico backend y Android.
7. **Hop TTL mutable fuera del payload firmado** — store-and-forward relay.
8. **CRC16 no es primitiva de seguridad** (`Crc16.kt:1-8`) — sólo optimización para rechazar frames corruptos antes del Ed25519 costoso.
9. **`BootReceiver exported=true`** con intent-filter `BOOT_COMPLETED` y `LOCKED_BOOT_COMPLETED` — protected broadcasts, sólo el sistema puede dispararlos.
10. **RLS habilitado en las 4 tablas** (`schema.sql:30,53,83,103`) sin políticas → sólo service-role key bypassa, clientes con anon key rechazados.

---

## Tabla "intencional vs error" — para discusión

| # | Hallazgo | Hipótesis auditor | Acción recomendada |
|---|----------|-------------------|--------------------|
| H1 | Dockerfile root | Error | Fix `USER bun` + chown |
| H2 | CORS wildcard default | Error | Requerir `CORS_ORIGINS` en prod |
| H3 | google-services.json tracked | Debatible | `.gitignore` + (opcional) rotar |
| H4 | WS subscription flood | Error | Cap `MAX_WS_SUBSCRIPTIONS_PER_CLIENT` |
| H5 | Body limit mismatch | Error (bug) | `bodyLimit` route-level |
| H6 | Android intent actions exported | Intencional demo, error en release | Mover a `src/debug/` o permission signature |
| H7 | DB sin encriptar | Discutible | Decidir threat model |
| H8 | Keystore sin user auth | Intencional UX | Considerar StrongBox |
| M1 | XOR manual vs timingSafeEqual | Error consistencia | Fix trivial |
| M2 | WS sin Origin check | Oversight | Validar Origin allowlist |
| M3 | Stores in-memory ilimitados | Aceptable dev | Documentar |
| M4 | Dev keys logueados | Intencional | Gate por isTTY |
| M5 | Auto-disarm cooldown | Intencional | Revisar parámetros |
| M6 | WakeLock sin timeout | Error menor | acquire(timeoutMs) |
| M7 | BootReceiver silencioso | Discutible UX | Notificación de boot |
| M8 | Sin TLS pinning | Aceptable (zero-trust) | Documentar |
| M9 | CI sin SHA pinning | Error hardening | Dependabot actions |
| M10 | resolve_receipts crece | Oversight | Cron cleanup |

---

## Recomendaciones de proceso para el team

1. **Sync de revisión**: dedicar una sesión de 60-90 min a recorrer esta tabla. Cada fila se decide en `fix` / `accept risk` / `intentional-doc`.
2. **Issues separados por hallazgo fixeable**: no PR mega-fix. Facilita review y rollback.
3. **Threat model document**: `android/docs/crypto.md` existe; completar con decisiones de H7 (DB sin encriptar) y H8 (Keystore sin user auth).
4. **Tests de regresión para cada fix**: e.g., test que valide que `bodyLimit` sube a 8 MB, test que CORS rechaza origin no listado en prod.
5. **Re-auditoría post-fix**: tras cerrar HIGHs, re-correr este reporte.

---

## Apéndice — scripts de verificación

```bash
# H1: confirmar root en imagen
docker run --rm guacamaya-net id
# Esperado: uid=0(root) — reproduce el hallazgo

# H3: confirmar google-services.json trackeado
git ls-files | grep -i google-services
# Esperado: android/app/google-services.json

# H2: reproducir wildcard default en prod
docker run --rm -e NODE_ENV=production -e GUACAMAYA_ADMIN_KEY=test \
  -p 3000:3000 guacamaya-net &
sleep 2
curl -i -H 'Origin: https://evil.example' http://localhost:3000/ \
  | grep -i 'access-control-allow-origin'
# Si imprime '*' → wildcard default confirmed

# H5: reproducir body limit
curl -i -X POST http://localhost:3000/resolve/evidence \
  -H 'Content-Type: application/octet-stream' \
  --data-binary "$(head -c 1100000 /dev/urandom | base64)"
# Esperado: 413 FST_ERR_CTP_BODY_TOO_LARGE (no el 413 del handler)

# M2: WS sin Origin check
curl -i -N -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
  -H 'Origin: https://evil.example' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  -H 'Sec-WebSocket-Version: 13' \
  http://localhost:3000/ws
# Esperado: 101 Switching Protocols — si upgradea, no hay Origin gate

# H4: WS subscription flood
wscat -c 'http://localhost:3000/ws' \
  -x '{"type":"subscribe","channel":"a"}' \
  -x '{"type":"subscribe","channel":"b"}' \
  # ... 10000 veces
# Observar uso de memoria del proceso backend

# M1: confirmar verifyUploadToken manual XOR
grep -n 'charCodeAt' backend/src/resolve/evidence.ts
# Esperado: línea 60
```

---

## Fuera de scope (explícito)

- **Pentesting externo / explotación real**: este reporte identifica superficie, no prueba explotabilidad end-to-end.
- **`bun audit` / Dependabot scan**: no se ejecutó scanner automático de vulnerabilidades de dependencias. Sólo se listaron versiones observadas.
- **RLS policies en Supabase**: se confirma RLS habilitado, pero no se probaron policies desde el cliente anon.
- **Código histórico en `init-sosnet`**: branch no auditada.
- **Fuzzing del parser BLE/Wi-Fi Aware**: no se hizo fuzzing de `Observer.parseServiceData128` ni `Broadcaster.frame`.
