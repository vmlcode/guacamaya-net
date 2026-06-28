# Downlink — Alertas Oficiales

La otra mitad del cableado **backend ↔ app** de [[GuacaMallaProject]]. Mientras el
[[IngestClient (Data-Mule Uploader)]] es el **uplink** (app → `POST /ingest`), esto es
el **downlink**: la app **descarga alertas oficiales** del [[Backend Data-Mule]]
(`GET /channels/:id/records`) y **solo muestra las que verifica criptográficamente**.

> Pedido del equipo (2026-06-28): *"faltaría conectar el BE y el Mobile app"*. El uplink
> ya estaba; faltaban la **alcanzabilidad** (URL configurable) y el **downlink** con
> verificación de firma. Esto cubre ambos. WebSocket en vivo (`/ws`) queda como siguiente paso.

Paquete: `net.guacamaya.backend`.

## Alcanzabilidad (lo que bloqueaba la conexión real)

El build de debug apuntaba **fijo** a `http://10.0.2.2:3000` (solo emulador) y el
`network_security_config` solo permitía cleartext a ese host → **un teléfono físico no
podía alcanzar** un backend en la LAN. Resuelto:

- `BuildConfig.BACKEND_BASE_URL` (renombrado desde `INGEST_BASE_URL`; uplink y downlink comparten host):
  - **debug** → default `http://10.0.2.2:3000`; override para LAN con `-PBACKEND_BASE_URL=http://192.168.x.y:3000`.
  - **release** → HTTPS desplegado; placeholder `https://guacamaya.invalid` hasta que exista. Override `-PBACKEND_RELEASE_URL=…`.
- **`src/debug/res/xml/network_security_config.xml`** permite cleartext a **cualquier host** (solo debug), para apuntar a un laptop por IP. Release sigue HTTPS-only (`src/main`). Ver [[Build y Entorno]].

## Componentes

| Clase | Rol |
|---|---|
| `BackendClient` | `HttpURLConnection` GET read-only: `/health`, `/pubkey`, `/channels/:id/records?since=`. Sin API key (la app **nunca** porta llaves de servidor). |
| `RecordJson` | Lector JSON hecho a mano (sin `org.json`) que devuelve cada `payload` como **texto crudo verbatim** del cable. Clave para la firma (abajo). |
| `OfficialRecordVerifier` | Verifica el registro oficial: integridad de contenido + firma Ed25519. |
| `AlertsRepository` | Orquesta: pubkey → records de canales oficiales → verifica → devuelve solo las **verificadas** (descarta y loguea las forjadas). |
| `MapViewModel.alerts` | `StateFlow<List<OfficialAlert>>`; `refreshAlerts()` best-effort en `init`/resume. |
| `AlertsBanner` (UI) | Banner read-only en la home con las alertas verificadas (acento verde + ✓). |

Canales oficiales que la app lee: `alertas`, `refugios`, `ayuda-medica`.

## El esquema de verificación (un 4º camino de cripto, distinto)

El backend firma registros oficiales con un esquema **diferente** al del frame de malla
(ver [[Arquitectura y Decisiones]]). Mirror de `verifyRecordSignature` de `@guacamaya/shared`:

```
content = "channel:timestamp:ttl:author:verified:" + JSON.stringify(payload)
hash    = SHA-256(utf8(content))
id      = hex(hash)              // el backend pone record.id = esto
sig     = Ed25519.sign(hash)     // ⚠️ el mensaje firmado es el hash de 32 B, NO el content
```

`OfficialRecordVerifier.verify` hace **dos** chequeos, ambos obligatorios:
1. `hex(SHA-256(content)) == record.id` — ata payload→id; frena un *payload-swap* que reusara un par `(id, sig)` válido.
2. `Ed25519.verify(sig, hash, backendPubkey)` — autenticidad (vía `Signer.verifyMessage`, que **no** exige los 22 B del frame).

### Por qué el payload se guarda como texto crudo

Para recomputar el hash hay que reproducir **byte a byte** el `JSON.stringify(payload)` del
servidor. `org.json` **no preserva el orden de claves**, así que re-serializar rompería la
firma. Por eso `RecordJson` captura el `payload` como el **substring exacto del cable**
(`OfficialRecord.payloadRaw`) y el verifier hashea esos bytes. Caveat: si un proxy
*pretty-printea* el JSON, o si Supabase JSONB reordena claves al servir, la verificación de
contenido fallaría — documentado; en dev (store en memoria) el orden se preserva.

## Verificación (2026-06-28)

- **Formato bloqueado contra backend real**: se creó una alerta oficial firmada, se capturó el
  cuerpo servido, y se confirmó que `SHA-256(content) == id` y que la firma verifica. Esos bytes
  exactos están en los tests JVM.
- **Tests JVM verdes**: `RecordJsonTest` (parseo + payload verbatim, JSON anidado, llaves dentro de
  strings, sig null, claves desconocidas) y `OfficialRecordVerifierTest` (registro real verifica;
  payload/metadata alterada, pubkey errónea, sig faltante, id que no casa → rechazados).
- **Build debug verde** con el banner en la UI.

## Pendiente / siguiente

- [ ] **Smoke en dispositivo**: app (emulador o teléfono con `-PBACKEND_BASE_URL=<IP-LAN>`) →
      `refreshAlerts()` → banner con la alerta del operador. Headless ya cubierto.
- [ ] **WebSocket `/ws`** (siguiente nivel de "wiring"): suscribir `solicito-ayuda` para SOS
      comunitarios en vivo sin polling. Ver `backend_final.md` §4.10.
- [ ] **`backend_final.md` está desactualizado** (dice que el `IngestClient` no existe, usa
      `org.sosnet`/`BACKEND_BASE_URL`); conviene reconciliarlo con la realidad.

Contrato de endpoints: [[Backend Data-Mule]] y `backend_final.md`. Seguridad/keys: [[Seguridad Backend]].
