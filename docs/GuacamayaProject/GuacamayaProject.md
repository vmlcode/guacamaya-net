# GuacamayaProject

Red de comunicación de emergencia para desastres (terremotos) donde la infraestructura de
telecomunicaciones cae por capas. La idea: que la información crítica siga fluyendo aunque no haya
torres celulares — los teléfonos cercanos se retransmiten datos entre sí.

> Contexto: hackathon. Pensado para Venezuela (parque mayoritariamente Android de gama baja).
> Idioma de trabajo de README/docs del repo: español; identificadores de código en inglés.

## Un solo monorepo (rama `develop`)

> **Cambio importante (2026-06-28):** lo que antes eran **dos productos en ramas separadas** ahora
> está **consolidado en un único monorepo** en la rama `develop`. La app Android se fusionó bajo
> `android/` (commit `3441089`). Ya no hay dos linajes que "no se fusionan": `develop` contiene
> ambas mitades. La rama `init-sosnet` quedó como el layout viejo standalone (atrasado).

| Mitad | Ruta | Stack | Rol |
|---|---|---|---|
| [[Guacamaya (Android)]] | `android/` | Android nativo, Kotlin + Compose | App principal: malla L2 sin conexión por BLE (+ Wi-Fi Aware pendiente). Proyecto Gradle autocontenido. |
| [[Backend Data-Mule]] | `backend/` + `packages/` | Bun + TypeScript (Fastify, Supabase) | Punto **opcional** de ingesta "data-mule", canales oficiales, histórico de ubicación y flujo de [[Resolve y Confirmacion de Rescate\|resolución de rescate]]. |

El puente entre ambos es el endpoint `POST /ingest` — ver [[Backend Data-Mule]] y [[Protocolo y Frame]].

## Rebrand: SOSNet → Guacamaya Net

El producto es **Guacamaya Net**. El nombre **SOSNet está retirado** y el paquete Android pasó de
`org.sosnet.*` a **`net.guacamaya.*`**. Usar *Guacamaya* en prosa, docs, identificadores y UI. Única
excepción: el nombre de rama remota `init-sosnet` es literal hasta que se renombre.

## Índice de notas

- [[Guacamaya (Android)]] — la app de malla (arquitectura, módulos, radar/brújula, UI).
- [[Protocolo y Frame]] — frame de 119 B, payload de 22 B, firma Ed25519, cascada de rechazo, **riesgo de sync de 3 vías**.
- [[Backend Data-Mule]] — backend TS, API HTTP/WS, ingesta zero-trust, histórico de ubicación.
- [[Seguridad Backend]] — endurecimiento: API keys, CORS, rate limits, auth de WebSocket.
- [[Resolve y Confirmacion de Rescate]] — quórum M-de-N de testigos co-firmantes para dar por resuelto un SOS.
- [[IngestClient (Data-Mule Uploader)]] — el uploader Kotlin que sube los frames recogidos al `POST /ingest`.
- [[Arquitectura y Decisiones]] — monorepo, rebrand, descarte de Briar/Expo/osmdroid, decisiones de diseño.
- [[Build y Entorno]] — cómo compilar y correr cada parte.
- [[Estado y Pendientes]] — qué funciona, qué está parcial, trabajo abierto.

## Tesis en una línea

El enlace de radio es **intencionalmente abierto** (un SOS público debe poder recibirlo y
retransmitirlo cualquiera en alcance); la autenticidad se mueve de la capa de enlace a la **capa de
aplicación** mediante firmas Ed25519. Sin pairing, sin handshake, sin credenciales, sin servidor
obligatorio. Ver [[Arquitectura y Decisiones]].
