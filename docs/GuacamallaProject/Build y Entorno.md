# Build y Entorno

Cómo compilar y correr las dos mitades del monorepo [[GuacamallaProject]] (rama `develop`).

## Ubicaciones locales

| Qué | Ruta | Rama |
|---|---|---|
| Monorepo principal (Android, layout viejo standalone) | `~/AndroidStudioProjects/guacamaya-net` | `init-sosnet` |
| Monorepo `develop` (Android + backend consolidados, git worktree) | `~/AndroidStudioProjects/guacamaya-develop` | `develop` |
| Remoto | `github.com/vmlcode/guacamaya-net` | — |

> `develop` es ahora el **monorepo consolidado**: contiene `android/` **y** `backend/` + `packages/`.
> El worktree comparte el mismo `.git`. La rama `init-sosnet` quedó con el layout viejo (la app en la
> raíz, sin backend) — atrasada. Ver [[Arquitectura y Decisiones]] §2.

## [[Guacamalla (Android)]] — compilar

Proyecto Gradle autocontenido en `android/`. **Abrir `android/` en Android Studio, no la raíz.**
Requiere Android SDK con `platform-android-34` + `build-tools;34.0.0`. Apuntar Gradle al SDK con
`local.properties` (`sdk.dir=...`, gitignored) o `ANDROID_HOME`.

> **JDK: usar 17–21.** Gradle 8.7 **no** soporta el JDK 26 que es el default del sistema en Arch.
> El `gradle.properties` **ya no fija** un `org.gradle.java.home` absoluto (rompía el sync en
> máquinas sin ese path — fue el error de sync reportado el 2026-06-28; commit `3756af4`). Cada dev
> configura su JDK:
> - **Android Studio**: Settings → Build Tools → Gradle → *Gradle JDK* → el JBR incluido o un JDK 17–21.
> - **CLI**: `JAVA_HOME` a un JDK 17–21, o `org.gradle.java.home=/ruta/jdk` en
>   `~/.gradle/gradle.properties` (nivel usuario, sobreescribe sin commitear un path específico).

```bash
cd android
export JAVA_HOME=/opt/android-studio/jbr        # JBR de Android Studio (JDK 21) sirve
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug                     # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                 # tests JVM (proto, crypto, ui geo/compass)
./gradlew :app:testDebugUnitTest --tests "net.guacamaya.proto.PayloadTest"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Helper de demo (`android/scripts/demo.sh` auto-resuelve el JDK: `JAVA_HOME` → JBR → `java` en PATH).
Creció mucho con el sprint de campo dual-device:

```bash
./scripts/demo.sh install         # build + adb install
./scripts/demo.sh logcat          # logcat coloreado
./scripts/demo.sh tamper          # frame con bit-flip para demostrar rechazo de firma
./scripts/demo.sh device-test     # prueba BLE mesh bidireccional adb (nodes/frames/target)
./scripts/demo.sh functional-compass   # sonda de brújula + mesh en paralelo
./scripts/demo.sh compass-miui sweet   # calibración figura-8 del magnetómetro en MIUI
```

Para la demo completa: dos teléfonos API 26+ (un broadcaster, un observer). Usar `ANDROID_SERIAL` para
elegir entre varios dispositivos. En MIUI/Xiaomi, los comandos adb se enrutan vía `AdbCommandReceiver`.

> El paquete es `net.guacamaya` (antes `org.sosnet`). El nombre de la DB Room es `guacamaya.db`.

## [[Backend Data-Mule]] — correr (Bun-first, nunca npm/node)

Bun instalado en `~/.bun/bin`. Desde la **raíz del worktree de `develop`**:

```bash
bun install
bun run dev:backend     # bun --watch backend/src/index.ts  → HTTP + WS en :3000
bun test                # tests del paquete shared (incluye resolve)  → 23 pass al 2026-06-28
bun run build           # typecheck/build de shared + backend
bun run keygen          # genera API keys / identidad estable
```

Corre sin base de datos (fallback en memoria si Supabase no está configurado). Copiar
`backend/.env.example` a `backend/.env` y fijar:

- `BACKEND_PRIVATE_KEY_HEX` — identidad Ed25519 estable del servidor.
- `GUACAMAYA_ADMIN_KEY` (y opcional `GUACAMAYA_READ_KEY`, `GUACAMAYA_WS_KEY`) — **obligatorio en
  producción** (`NODE_ENV=production` no arranca sin admin key). Ver [[Seguridad Backend]].
- `CORS_ORIGINS` — lista de orígenes; nunca `*` en prod.
- `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` — opcionales; sin ellos, store en memoria.

> `android/` **no** es un workspace de Bun — mantenerlo fuera de los workspaces del `package.json` raíz.

Estado actual y trabajo abierto: [[Estado y Pendientes]].
