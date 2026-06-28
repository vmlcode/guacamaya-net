# GuacamayaProject

Red de comunicación de emergencia para desastres (terremotos) donde la infraestructura de
telecomunicaciones cae por capas. La idea: que la información crítica siga fluyendo aunque no haya
torres celulares — los teléfonos cercanos se retransmiten datos entre sí.

> Contexto: hackathon. Pensado para Venezuela (parque mayoritariamente Android de gama baja).
> Idioma de trabajo de README/docs del repo: español.

## Dos productos en un mismo repo

El repositorio `guacamaya-net` contiene **dos productos distintos** en ramas separadas (comparten el
commit raíz pero no comparten código). No se fusionan entre sí.

| Producto | Ramas | Stack | Rol |
|---|---|---|---|
| [[Guacamaya (Android)]] | `init-sosnet`, `BTE-comunicacion` | Android nativo, Kotlin + Compose | App principal: malla L2 sin conexión por BLE + Wi-Fi Aware. |
| [[Backend Data-Mule]] | `develop`, `BR-01-Backend` | Bun + TypeScript (Fastify, Supabase) | Punto **opcional** de ingesta "data-mule" de los reportes de la malla. |

El puente entre ambos es el endpoint `POST /ingest` — ver [[Backend Data-Mule]] y [[Protocolo y Frame]].

## Índice de notas

- [[Guacamaya (Android)]] — la app de malla (arquitectura, módulos, UI).
- [[Protocolo y Frame]] — formato del frame de 119 B, payload de 22 B, firma Ed25519, cascada de rechazo.
- [[Backend Data-Mule]] — backend TS, API HTTP/WS, ingesta zero-trust.
- [[Arquitectura y Decisiones]] — por qué malla pura, por qué se descartó Briar/Expo, decisiones de diseño.
- [[Build y Entorno]] — cómo compilar y correr cada parte.
- [[Estado y Pendientes]] — qué funciona, qué está parcial, trabajo abierto.

## Tesis en una línea

El enlace de radio es **intencionalmente abierto** (un SOS público debe poder recibirlo y
retransmitirlo cualquiera en alcance); la autenticidad se mueve de la capa de enlace a la **capa de
aplicación** mediante firmas Ed25519. Sin pairing, sin handshake, sin credenciales, sin servidor
obligatorio. Ver [[Arquitectura y Decisiones]].
