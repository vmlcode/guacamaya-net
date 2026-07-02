# Arquitectura y Decisiones

Registro de las decisiones de diseño no obvias de [[GuacaMallaProject]]. Estilo ADR ligero.

## 1. Malla pura sin conexión, no cliente-servidor

El enlace de radio es **intencionalmente abierto**: un SOS público debe poder recibirlo y
retransmitirlo cualquiera en alcance. No hay pairing, handshake ni credenciales en la capa de enlace.

**La autenticidad se mueve a la capa de aplicación** vía firmas Ed25519: cada payload de 22 B va
firmado; cualquier manipulación rompe la firma y el siguiente salto la descarta en silencio. Resultado:
malla asíncrona, oportunista, sin conexión que caer ni servidor que falle. Ver [[Protocolo y Frame]].

## 2. Monorepo consolidado (2026-06-28)

Antes el repo tenía **dos productos en linajes de rama separados** que compartían commit raíz pero no
código y "no se fusionaban". Eso cambió: la app Android se **fusionó dentro de la rama `develop`**
bajo `android/` (commit `3441089`). Hoy `develop` es el monorepo con ambas mitades:

- `backend/` + `packages/` — backend Bun/TS ([[Backend Data-Mule]]).
- `android/` — app Kotlin/Compose ([[GuacaMalla (Android)]]), **proyecto Gradle autocontenido** (su
  propio `gradlew`, `build.gradle.kts`, `docs/`, `CLAUDE.md`). Se abre `android/` directo en Android
  Studio, no la raíz del repo. `android/` **no** es un workspace de Bun.

La rama `init-sosnet` quedó con el layout viejo standalone (atrasado respecto a `develop`).

## 3. Rebrand SOSNet → GuacaMalla Net

El producto se llama **GuacaMalla Net**; **SOSNet está retirado**. El paquete Android pasó de
`org.sosnet.*` a `net.guacamaya.*` (se renombró todo el árbol de paquetes). Las constantes de
wire-format viven en `packages/shared/src/mesh/` y deben quedar byte-idénticas con
`net.guacamaya.proto.*`. Excepción: la rama remota `init-sosnet` es un nombre literal hasta renombrar.

## 4. Se descartó construir sobre Briar

Briar fue el punto de partida evaluado (mensajería P2P por mesh). Se descartó: su modelo exige
**agregar contactos mutuos antes de comunicar**, inútil cuando eres un sobreviviente rodeado de
desconocidos. Solo se tomó el **concepto** store-and-forward, no el código.

## 5. La app Expo de `develop` se abandonó

`develop` traía una app Expo/React Native que vivía en `app/`. Se **eliminó**: la app nativa Kotlin
([[GuacaMalla (Android)]]) hace mejor el trabajo (acceso real a BLE 5 Extended Advertising y Wi-Fi
Aware, que RN no expone bien). El backend de `develop` se reutiliza como [[Backend Data-Mule]].

## 6. TTL de salto FUERA del payload firmado (el byte líder)

El `hopTtl` necesita decrementarse en cada salto, pero re-firmar es imposible: el `node_id` está atado
a la pubkey del origen. La solución: un **byte de TTL sin firmar al frente del frame** (118→119 B). El
payload firmado viaja intacto; solo el byte líder encoge. Cada relay reenvía con `ttl − 1` y para en 0.
Detalle en [[Protocolo y Frame]].

## 7. Se eliminó osmdroid y la dependencia de Google Play Services para el mapa

El mapa antes usaba **osmdroid** (tile cache + overhead de WebView, pesado para RAM de gama baja) y la
ubicación dependía de `FusedLocationProviderClient` (GMS), que muchos teléfonos del mercado objetivo
no traen. Se reemplazó por una **cuadrícula offline propia en metros** (`GridMap` / `GeoGrid`, plano
ENU este/norte) y un radar con brújula. Esto recorta RAM y quita una dependencia de GMS para el
render. Ver el nuevo stack en [[GuacaMalla (Android)]].

> Nota: `play-services-location` todavía figura como dependencia de Gradle para el *fix* de GPS; el
> fallback robusto sin GMS (`LocationManager` de plataforma) sigue como pendiente — ver [[Estado y Pendientes]].

## 8. Resolve: el quórum reemplaza la confirmación de la víctima

Una señal SOS no puede depender de que la víctima siga viva para "cerrarse". Decisión: un **buscador**
(rescatista/vecino) que llega al sitio puede dar el SOS por resuelto con un **quórum M-de-N de testigos
co-firmantes** (Ed25519), sin requerir el dispositivo de la víctima. Implementado en backend; clientes
pendientes. Diseño completo en [[Resolve y Confirmacion de Rescate]].

## 9. Ubicación derivada del frame, nunca confiada del cliente

El histórico de ubicación (`GET /locations`, mapa móvil) se alimenta **solo** de la lat/lon que viaja
dentro de un frame verificado en `POST /ingest`. Se **eliminó** el endpoint de ingesta JSON confiado
de ubicaciones: la misma compuerta Ed25519 que protege los registros protege las posiciones. El
`deviceId` se deriva de la pubkey verificada, nunca lo aporta el cliente. Ver [[Backend Data-Mule]].

## 10. Dos esquemas de cripto, un solo puente

No son intercambiables — son **cuatro caminos de cripto distintos**:
- **GuacaMalla mesh** firma el payload binario crudo de 22 B (Ed25519 sobre los bytes).
- **Backend** (registros oficiales) firma `SHA-256(content canónico)` del `ChannelRecord` (mensaje = el hash de 32 B, no el content). La app lo verifica en el downlink — ver [[Downlink Alertas Oficiales]].
- **Resolve** introduce un tercer formato canónico (`guacamaya.resolve.v1`) co-firmado por testigos.
- En Android, `Signer.verify` (22 B) y `Signer.verifyMessage` (longitud arbitraria) cubren los dos primeros; mantenerlos separados.

El puente de la malla al backend es `POST /ingest` (uplink), que re-verifica con la lógica de la malla.
La app **nunca** porta llaves de servidor; lo que consume (`/ingest`, `/pubkey`, `/channels`) no requiere
llave. Ver [[Backend Data-Mule]], [[Seguridad Backend]] y [[Resolve y Confirmacion de Rescate]].

## 11. Filtro BLE por software, no por hardware

El `Observer` filtra el UUID de servicio **en software**, no con `ScanFilter` de hardware: muchos
stacks omiten el service-data extendido cuando filtran en silicio. No "optimizar" esto de vuelta a
filtro por hardware.

## 12. Verify siempre al final

La verificación Ed25519 es cara; va de última en la cascada, tras los chequeos baratos (binding de
pubkey, CRC, ventana de tiempo). Nunca persistir ni retransmitir antes de pasar toda la cascada.

## 13. UI colapsada a SOS-only + escucha siempre activa (reunión 2026-06-29)

Decisión de equipo en la [[meeting-notes|reunión del 29 jun 2026]]: **eliminar el modo manual
"Buscar/Find" de la interfaz**. El teléfono **siempre escucha en segundo plano** (repetidor pasivo
por BLE, bajo consumo), así que no hace falta un modo que el usuario active: ante un SOS, el equipo
ya está relayando/subiendo sin intervención. La UI se reduce a **un único botón SOS** (ya por
defecto, ver [[Estado y Pendientes]]) y el **radar pasa a ser una pantalla a la que se hace *swipe***,
no un modo seleccionable.

> Estado en código: hecho a medias. Ya está *default = SOS* y *SOS también observa* (escucha mientras
> emite). **Falta** colapsar el modelo `MeshMode {SOS, FIND, BOTH}` → quitar `FIND`/`ModeSelector` de
> la UI y dejar la escucha como servicio siempre-on + swipe-al-radar. Es el siguiente paso de Android.

> El **radar** se mantiene (no se elimina): el equipo lo percibió como "pura animación" en un build
> viejo; ya usa acelerómetro+magnetómetro (`CompassHeading`). Pendiente real = calibración MIUI +
> la UX de swipe. Ver [[GuacaMalla (Android)]].

> Idea relacionada (David, roadmap, no MVP): **SOS multi-categoría** por *swipe* (sismo, deforestación,
> robo, violencia…). El wire ya lo soporta — `sosType` tiene 8 valores (ver [[Protocolo y Frame]]); es
> solo una afordancia de UI, no un cambio de protocolo.
