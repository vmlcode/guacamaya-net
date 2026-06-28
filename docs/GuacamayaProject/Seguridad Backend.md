# Seguridad Backend

Endurecimiento del [[Backend Data-Mule]] añadido en el sprint de junio 2026. Toda la configuración
vive en `backend/src/security/config.ts`; los helpers en `auth.ts`, `rateLimit.ts`, `validation.ts`,
`keygen.ts`. Parte de [[GuacamayaProject]].

## Modelo de claves (API keys)

Tres claves, leídas de env con fallback en cascada:

| Clave env | Para qué | Fallback |
|---|---|---|
| `GUACAMAYA_ADMIN_KEY` | Firmar/crear registros oficiales (`POST /channels/:id/records`). | alias `GUACAMAYA_API_KEY` |
| `GUACAMAYA_READ_KEY` | Leer trayectorias y canales WS sensibles (`GET /locations`, subscribe). | cae a admin key |
| `GUACAMAYA_WS_KEY` | Token de upgrade del WebSocket. | cae a read key → admin key |

- **En `NODE_ENV=production` el server se niega a arrancar sin `GUACAMAYA_ADMIN_KEY`** (lanza error).
- En dev, si una clave no está seteada se genera una **efímera** y se loguea una vez al arrancar.
- Comparación **timing-safe** de API keys con `crypto.timingSafeEqual` (evita timing attacks).
- Generar claves estables con **`bun run keygen`** desde la raíz del repo (antes no existía; el
  `.env.example` lo mencionaba sin implementarlo — ya está).

## CORS

`CORS_ORIGINS` (lista separada por comas). **Nunca `*` en producción.** Si está vacío o `*`, se permite
todo (solo aceptable en dev).

## Rate limits

- **Global**: 100 req/min (`@fastify/rate-limit`).
- **`/ingest`**: 30/min por ruta, batch tope `MAX_INGEST_BATCH` (default 200, máx 1000).
- **`/channels/:id/records` (oficial)**: 20/min.
- **`/resolve`**: leaky-bucket **por `deviceId`**, 5/hora (`RESOLVE_PER_WITNESS_PER_H`), independiente
  del rate-limit por IP de Fastify. Ver [[Resolve y Confirmacion de Rescate]].
- `@fastify/helmet` activado (headers de seguridad).

## Límites de tamaño

- `MAX_FRAME_B64_LENGTH` (default 256) — por frame base64 en `/ingest`.
- `MAX_OFFICIAL_PAYLOAD_BYTES` (default 16 KiB) — payload de registro oficial.
- `RESOLVE_MAX_IMAGE_BYTES` (default 8 MiB) — imagen de evidencia.

## Autenticación de endpoints

- **Registros oficiales** (`POST /channels/:id/records`): requieren `X-Api-Key` o
  `Authorization: Bearer <admin key>`; solo canales oficiales (`alertas`, `refugios`, `ayuda-medica`).
- **Ubicación** (`GET /locations`): requiere read key (o admin).
- **WebSocket** (`/ws`): `?token=<key>` o header `Sec-WebSocket-Protocol: guacamaya.<key>`; usa WS key
  o read key.
- **`/ingest`**: sin API key a propósito — es **zero-trust por firma** (cualquier mule puede subir; el
  backend re-verifica cada frame). Ver [[Backend Data-Mule]].
- **`/resolve/evidence`**: requiere read key en producción (configurable con `RESOLVE_EVIDENCE_REQUIRE_AUTH`).

## Validación

`security/validation.ts` valida la forma de los cuerpos (p. ej. `isValidResolveEnvelope`) antes de
procesar — los handlers asumen estructura ya chequeada.

## Pendientes de seguridad

- Rate-limit por origen en `/ingest` además del global (anotado en backlog).
- Agregación/moderación de reportes de comunidad.
- Reputación persistente de testigos para Resolve (ver [[Resolve y Confirmacion de Rescate]]).

Setup de `.env`: [[Build y Entorno]]. Estado general: [[Estado y Pendientes]].
