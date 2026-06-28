# Arquitectura y Decisiones

Registro de las decisiones de diseño no obvias de [[GuacamayaProject]]. Estilo ADR ligero.

## 1. Malla pura sin conexión, no cliente-servidor

El enlace de radio es **intencionalmente abierto**: un SOS público debe poder recibirlo y
retransmitirlo cualquiera en alcance. No hay pairing, handshake ni credenciales en la capa de enlace.

**La autenticidad se mueve a la capa de aplicación** vía firmas Ed25519: cada payload de 22 B va
firmado; cualquier manipulación rompe la firma y el siguiente salto la descarta en silencio. Resultado:
malla asíncrona, oportunista, sin conexión que caer ni servidor que falle. Ver [[Protocolo y Frame]].

## 2. Se descartó construir sobre Briar

Briar fue el punto de partida evaluado (mensajería P2P por mesh). Se descartó: su modelo exige
**agregar contactos mutuos antes de comunicar**, inútil cuando eres un sobreviviente rodeado de
desconocidos. Además es un codebase grande Java/Android con filosofía P2P-sin-servidor opuesta. Solo
se tomó el **concepto** store-and-forward, no el código.

## 3. La app Expo de `develop` se abandonó

`develop` traía una app Expo/React Native además del backend. Se descartó: la app nativa Kotlin
([[Guacamaya (Android)]]) hace mejor el trabajo (acceso real a BLE 5 Extended Advertising y Wi-Fi
Aware, que RN no expone bien). El backend de `develop` se **reutiliza** como [[Backend Data-Mule]].

## 4. TTL de salto FUERA del payload firmado (el byte líder)

El problema: el `hopTtl` necesita decrementarse en cada salto, pero está en los bytes firmados. Si se
modifica, la firma del origen se invalida en el siguiente salto. Y **re-firmar es imposible**: el
`node_id` está atado a la pubkey del origen; firmar con otra llave cambiaría la identidad/ubicación
del reporte.

La solución (lo que el código antes difería como "P11"): un **byte de TTL sin firmar al frente del
frame** (118→119 B). El payload firmado viaja intacto (la firma siempre verifica), solo el byte líder
encoge. Cada relay reenvía con `ttl − 1` y para en 0. Detalle en [[Protocolo y Frame]].

## 5. Dos esquemas de cripto, un solo puente

No son intercambiables:
- **Guacamaya** (app de malla) firma el payload binario crudo de 22 B (Ed25519 sobre los bytes).
- **Backend** (registros oficiales) firma un hash canónico JSON del `ChannelRecord`.

El único punto de contacto es `POST /ingest`, que entiende el formato binario de la malla y re-verifica
con la lógica de la app Guacamaya. Ahí los frames entran como comunidad `verified:false`, nunca como oficiales.
Ver [[Backend Data-Mule]].

## 6. Filtro BLE por software, no por hardware

El `Observer` filtra el UUID de servicio **en software**, no con `ScanFilter` de hardware: muchos
stacks omiten el service-data extendido cuando filtran en silicio. No "optimizar" esto de vuelta a
filtro por hardware.

## 7. Verify siempre al final

La verificación Ed25519 es cara; va de última en la cascada, tras los chequeos baratos (binding de
pubkey, CRC, ventana de tiempo). Nunca persistir ni retransmitir antes de pasar toda la cascada.
