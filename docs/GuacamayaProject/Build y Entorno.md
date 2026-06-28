# Build y Entorno

Cómo compilar y correr las dos partes de [[GuacamayaProject]].

## Ubicaciones locales

| Qué | Ruta | Rama |
|---|---|---|
| App Android (trabajo actual) | `~/AndroidStudioProjects/guacamaya-net` | `init-sosnet` |
| Backend (git worktree) | `~/AndroidStudioProjects/guacamaya-develop` | `develop` |
| Remoto | `github.com/vmlcode/guacamaya-net` | — |

El worktree de `develop` se creó con `git worktree add` para no perturbar los cambios sin commitear
de la app Android. Comparte el mismo repo `.git`.

## [[Guacamaya (Android)]] — compilar

Requiere **JDK 17+** (AGP 8.5.2 corre bien en JDK 21) y Android SDK con `platform-android-34` +
`build-tools;34.0.0`. Apuntar Gradle al SDK con `local.properties` (`sdk.dir=...`, está en
.gitignore) o `ANDROID_HOME`.

```bash
export JAVA_HOME=/opt/android-studio/jbr        # JBR de Android Studio (JDK 21) sirve
export ANDROID_HOME=~/Android/Sdk
./gradlew :app:assembleDebug                     # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                 # tests JVM (proto, crypto)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Helper de demo (`scripts/demo.sh` auto-resuelve el JDK: `JAVA_HOME` → JBR → `java` en PATH):

```bash
./scripts/demo.sh install   # build + adb install en el dispositivo conectado
./scripts/demo.sh logcat    # logcat coloreado
./scripts/demo.sh tamper    # genera un frame con bit-flip para demostrar el rechazo de firma
```

Para la demo completa: dos teléfonos API 26+ con Wi-Fi Aware (un broadcaster, un observer).

> Setup inicial que hubo que hacer: instalar `platform-android-34` + `build-tools;34.0.0` vía
> `sdkmanager` (se bajaron las cmdline-tools de Google porque no estaban), crear `local.properties`,
> y patchar `demo.sh` para que no dependa de un JDK 17 hardcodeado.

## [[Backend Data-Mule]] — correr (Bun-first, nunca npm/node)

Bun instalado en `~/.bun/bin`. Desde la raíz del worktree de `develop`:

```bash
bun install
bun run dev:backend     # bun --watch backend/src/index.ts  → HTTP + WS en :3000
bun test                # tests del paquete shared
bun run build           # typecheck/build de shared + backend
```

Corre sin base de datos (fallback en memoria si Supabase no está configurado). Copiar
`backend/.env.example` a `backend/.env` y fijar `BACKEND_PRIVATE_KEY_HEX` para identidad estable.

Estado actual y trabajo abierto: [[Estado y Pendientes]].
