# 🦜 GuacaMalla Red — Backlog para Jira

> Listo para copiar y pegar en Jira. Estructura: **Épicas → Historias → Sub-tareas**.
> Cada historia trae: Resumen, Descripción, Criterios de aceptación, Prioridad y Estimación (puntos).
> Etiquetas sugeridas: `guacamalla-red`, `hackathon`, + el área (`backend`, `app`, `mesh`, `sms`, `infra`).

---

## 📋 Resumen de épicas

| # | Épica | Objetivo | Prioridad |
|---|---|---|---|
| EPIC-0 | Spike de Mesh (viabilidad) | Probar que 2 Android intercambian datos sin internet | 🔴 Bloqueante |
| EPIC-1 | Backend + Modelo de datos | Backend con canales firmados, HTTP/WS e ingesta | 🔴 Alta |
| EPIC-2 | App: online + render | App Expo que baja y muestra canales | 🔴 Alta |
| EPIC-3 | Sincronización por SMS | Sync de titulares críticos vía SMS | 🟡 Media |
| EPIC-4 | Mesh + Data-mule | Propagación multi-salto y subida al backend | 🔴 Alta |
| EPIC-5 | Demo de desastre | Escenario degradado end-to-end | 🟡 Media |
| EPIC-6 | Infra & soporte | Monorepo, paquete compartido, CI | 🟢 Baja |

---

# 🟥 EPIC-0 — Spike de Mesh (viabilidad)

> **Objetivo:** Validar técnicamente que dos dispositivos Android pueden descubrirse e intercambiar un registro **sin internet**. Bloquea el resto del proyecto.
> **Etiquetas:** `guacamalla-red`, `mesh`, `spike`

---

### HU-0.1 — Spike BLE doble rol

**Tipo:** Historia
**Resumen:** Probar intercambio de datos entre 2 Android usando BLE en doble rol (central + peripheral)

**Descripción:**
Como desarrollador, quiero validar que React Native puede usar BLE en rol central y peripheral simultáneamente en Android, para saber si BLE es viable como transporte mesh.

**Criterios de aceptación:**
- [ ] App de prueba con `react-native-ble-plx` (central) + librería de advertising (peripheral)
- [ ] Dispositivo A se anuncia y dispositivo B lo descubre
- [ ] Se transfiere al menos 1 registro (`ChannelRecord`) de A → B sin internet
- [ ] Documentadas las limitaciones encontradas (tamaño, estabilidad, batería)

**Prioridad:** Highest · **Estimación:** 5

---

### HU-0.2 — Spike Google Nearby Connections

**Tipo:** Historia
**Resumen:** Probar intercambio de datos entre 2 Android usando Google Nearby Connections

**Descripción:**
Como desarrollador, quiero evaluar Nearby Connections (BT + WiFi automático) como alternativa más robusta a BLE puro para transferencia de datos en mesh.

**Criterios de aceptación:**
- [ ] App de prueba con módulo nativo de Nearby Connections en Expo dev-client
- [ ] Dos dispositivos se descubren y conectan automáticamente
- [ ] Se transfiere un registro entre ambos sin internet
- [ ] Comparados rendimiento/estabilidad/tamaño de payload vs BLE

**Prioridad:** Highest · **Estimación:** 5

---

### HU-0.3 — Decisión de transporte mesh

**Tipo:** Historia
**Resumen:** Elegir el transporte mesh definitivo y documentar la decisión

**Descripción:**
Como equipo, queremos decidir entre BLE y Nearby Connections con base en los spikes, para fijar la base técnica del mesh.

**Criterios de aceptación:**
- [ ] Documento comparativo (alcance, fiabilidad, payload, batería, complejidad)
- [ ] Decisión registrada con justificación
- [ ] Definida la interfaz `Transport` que abstrae el transporte elegido

**Prioridad:** Highest · **Estimación:** 2

---

# 🟥 EPIC-1 — Backend + Modelo de datos

> **Objetivo:** Backend en Node+TS con canales, registros firmados (Ed25519), HTTP/WebSocket e ingesta de comunidad.
> **Etiquetas:** `guacamalla-red`, `backend`

---

### HU-1.1 — Modelo `ChannelRecord` compartido

**Tipo:** Historia
**Resumen:** Definir el modelo de datos `ChannelRecord` en el paquete compartido

**Descripción:**
Como desarrollador, quiero un tipo `ChannelRecord` único en `packages/shared/`, para que backend y app usen el mismo modelo y protocolo de merge.

**Criterios de aceptación:**
- [ ] Interfaz `ChannelRecord` (id, channel, timestamp, ttl, author, verified, payload, sig)
- [ ] Función de generación de `id` (hash determinista del contenido)
- [ ] Función `merge(logA, logB)` con unión + dedupe por id (con tests)
- [ ] Publicado/consumible desde backend y app

**Prioridad:** High · **Estimación:** 3

---

### HU-1.2 — Firma y verificación Ed25519

**Tipo:** Historia
**Resumen:** Firmar registros oficiales en backend y verificarlos en cliente

**Descripción:**
Como sistema, quiero que los canales oficiales vayan firmados con Ed25519, para que la app distinga información verificada de reportes no confiables.

**Criterios de aceptación:**
- [ ] Generación y gestión del par de llaves del backend
- [ ] Función de firma de `ChannelRecord` (campo `sig`)
- [ ] Función de verificación reutilizable (en `packages/shared/`)
- [ ] Llave pública embebible en la app
- [ ] Tests de firma válida/ inválida/ alterada

**Prioridad:** High · **Estimación:** 3

---

### HU-1.3 — API de canales (HTTP)

**Tipo:** Historia
**Resumen:** Endpoints HTTP para listar y obtener canales y registros

**Descripción:**
Como dispositivo, quiero obtener canales y sus registros por HTTP, para sincronizar cuando tengo internet.

**Criterios de aceptación:**
- [ ] `GET /channels` lista canales disponibles
- [ ] `GET /channels/:id/records?since=<ts>` devuelve registros desde un timestamp
- [ ] Respuestas con registros firmados
- [ ] Servidor Fastify configurado y documentado

**Prioridad:** High · **Estimación:** 3

---

### HU-1.4 — Sincronización en tiempo real (WebSocket)

**Tipo:** Historia
**Resumen:** Canal WebSocket para empujar nuevos registros en vivo

**Descripción:**
Como dispositivo con internet, quiero recibir registros nuevos en tiempo real por WebSocket, para ver alertas al instante.

**Criterios de aceptación:**
- [ ] Conexión WS estable con suscripción por canal
- [ ] Push de registros nuevos a clientes suscritos
- [ ] Reconexión automática del cliente
- [ ] Manejo de backpressure / clientes lentos

**Prioridad:** High · **Estimación:** 3

---

### HU-1.5 — Endpoint de ingesta (data-mule)

**Tipo:** Historia
**Resumen:** `POST /ingest` para recibir registros de comunidad subidos desde dispositivos

**Descripción:**
Como backend, quiero recibir registros recogidos por mesh y subidos por dispositivos, para cerrar el ciclo bidireccional.

**Criterios de aceptación:**
- [ ] `POST /ingest` acepta uno o varios `ChannelRecord`
- [ ] Dedupe por id (no se duplican registros ya conocidos)
- [ ] Registros entran como `verified: false`
- [ ] Rate limiting por origen
- [ ] Registros no confiables NO se firman como oficiales automáticamente

**Prioridad:** High · **Estimación:** 5

---

### HU-1.6 — Moderación / agregación de reportes de comunidad

**Tipo:** Historia
**Resumen:** Cola de moderación y agregación de reportes no verificados

**Descripción:**
Como operador, quiero revisar/agregar reportes de comunidad, para evitar que la desinformación se redistribuya con apariencia oficial.

**Criterios de aceptación:**
- [ ] Reportes de comunidad quedan en cola pendiente
- [ ] Posibilidad de aprobar (promover a oficial firmado) o descartar
- [ ] Agregación básica ("N reportes en zona X")
- [ ] Vista mínima de moderación (puede ser CLI/endpoint para el hackathon)

**Prioridad:** Medium · **Estimación:** 5

---

# 🟥 EPIC-2 — App: online + render

> **Objetivo:** App Expo dev-client (Android) que baja canales por WS/HTTP, los guarda localmente y los muestra con estado de verificación.
> **Etiquetas:** `guacamalla-red`, `app`

---

### HU-2.1 — Scaffold de la app Expo dev-client

**Tipo:** Historia
**Resumen:** App React Native con Expo Dev Client corriendo en Android

**Descripción:**
Como desarrollador, quiero la app base con Expo dev-client (no managed), para poder integrar módulos nativos de BLE/SMS.

**Criterios de aceptación:**
- [ ] Proyecto Expo dev-client compila y corre en Android físico
- [ ] Consume `packages/shared/`
- [ ] Navegación base y estructura de carpetas (`store`, `transports`, `crypto`, `ui`)

**Prioridad:** High · **Estimación:** 3

---

### HU-2.2 — Log local persistente

**Tipo:** Historia
**Resumen:** Almacenamiento local del log de gossip con merge + dedupe

**Descripción:**
Como app, quiero persistir todos los registros que veo, para no perder datos al cerrar y poder propagarlos luego.

**Criterios de aceptación:**
- [ ] Persistencia con SQLite (`expo-sqlite`) o MMKV
- [ ] Insertar registros con dedupe por id
- [ ] Consultar registros por canal y por timestamp
- [ ] Sobrevive a reinicios de la app

**Prioridad:** High · **Estimación:** 3

---

### HU-2.3 — Transporte internet (HTTP/WS) en la app

**Tipo:** Historia
**Resumen:** Cliente que sincroniza con el backend por HTTP y WebSocket

**Descripción:**
Como usuario con internet, quiero que la app baje los canales automáticamente, para ver la información actualizada.

**Criterios de aceptación:**
- [ ] Sync inicial por HTTP (`since` = último timestamp conocido)
- [ ] Suscripción WS para registros nuevos en vivo
- [ ] Registros recibidos pasan al log local (con merge)
- [ ] Manejo de pérdida/reconexión de red

**Prioridad:** High · **Estimación:** 3

---

### HU-2.4 — Verificación de firmas en cliente

**Tipo:** Historia
**Resumen:** Verificar la firma de los registros oficiales en la app

**Descripción:**
Como usuario, quiero saber qué información es oficial y cuál es de la comunidad, para confiar en lo correcto durante una emergencia.

**Criterios de aceptación:**
- [ ] Llave pública del backend embebida en la app
- [ ] Verificación de `sig` al recibir registros oficiales
- [ ] Registros con firma inválida se rechazan o marcan como sospechosos

**Prioridad:** High · **Estimación:** 2

---

### HU-2.5 — UI de canales

**Tipo:** Historia
**Resumen:** Pantallas de lista de canales y detalle con badge de verificación

**Descripción:**
Como usuario, quiero ver los canales (alertas, refugios, ayuda) con su contenido y saber qué está verificado, para tomar decisiones.

**Criterios de aceptación:**
- [ ] Lista de canales disponibles
- [ ] Detalle de canal con registros ordenados por tiempo
- [ ] Badge visual "✅ verificado" / "⚠️ no confirmado"
- [ ] UI usable en pantallas pequeñas / gama baja

**Prioridad:** High · **Estimación:** 3

---

### HU-2.6 — Crear reporte de comunidad

**Tipo:** Historia
**Resumen:** El usuario puede crear reportes ("estoy bien", "solicito ayuda")

**Descripción:**
Como sobreviviente, quiero publicar mi estado o pedir ayuda, para que la información se propague por la red.

**Criterios de aceptación:**
- [ ] Formulario para crear registro en canales de comunidad
- [ ] Registro generado con id, timestamp, `verified: false`, identidad pseudónima del device
- [ ] Se guarda en el log local listo para propagar

**Prioridad:** Medium · **Estimación:** 2

---

# 🟨 EPIC-3 — Sincronización por SMS

> **Objetivo:** Sincronizar titulares críticos vía SMS cuando hay celular pero no datos.
> **Etiquetas:** `guacamalla-red`, `sms`

---

### HU-3.1 — Gateway SMS en backend

**Tipo:** Historia
**Resumen:** Número/gateway que responde solicitudes por SMS con titulares críticos

**Descripción:**
Como dispositivo sin datos, quiero enviar un SMS a un número y recibir los titulares críticos, para informarme en red degradada.

**Criterios de aceptación:**
- [ ] Gateway (Twilio o Android-gateway) recibe SMS entrantes
- [ ] Responde con titulares críticos comprimidos
- [ ] Maneja límite de tamaño y múltiples segmentos

**Prioridad:** Medium · **Estimación:** 5

---

### HU-3.2 — Codificación compacta binaria

**Tipo:** Historia
**Resumen:** Codec compacto para meter el máximo de info en ~140 bytes por SMS

**Descripción:**
Como sistema, quiero serializar registros críticos de forma compacta, porque un SMS solo carga ~140 bytes.

**Criterios de aceptación:**
- [ ] Codec binario (encode/decode) en `packages/shared/`
- [ ] Prioriza alertas y titulares recientes
- [ ] Tests round-trip encode→decode
- [ ] Documentado el presupuesto de bytes por segmento

**Prioridad:** Medium · **Estimación:** 3

---

### HU-3.3 — Envío/recepción de SMS en la app (Android)

**Tipo:** Historia
**Resumen:** La app envía solicitud por SMS y procesa la respuesta

**Descripción:**
Como usuario sin datos, quiero pedir actualización por SMS desde la app, para no depender de internet.

**Criterios de aceptación:**
- [ ] Permisos SMS en Android gestionados
- [ ] Envío de SMS al número del gateway
- [ ] Lectura de la respuesta y decodificación al log local (con merge)
- [ ] Manejo de fallos/timeout

**Prioridad:** Medium · **Estimación:** 5

---

# 🟥 EPIC-4 — Mesh + Data-mule

> **Objetivo:** Implementar el sync de gossip sobre el transporte elegido (Fase 0), con TTL, multi-salto y subida al backend.
> **Etiquetas:** `guacamalla-red`, `mesh`

---

### HU-4.1 — Protocolo de sync de gossip

**Tipo:** Historia
**Resumen:** Implementar el intercambio de registros faltantes entre dos nodos

**Descripción:**
Como dispositivo, quiero sincronizar mi log con un vecino intercambiando solo lo que falta, para propagar información eficientemente.

**Criterios de aceptación:**
- [ ] Intercambio de resumen (lista de ids / bloom por canal desde un ts)
- [ ] Cada nodo solicita y recibe los ids faltantes
- [ ] Verificación de firmas + merge al log local
- [ ] Funciona sobre la interfaz `Transport` (HU-0.3)

**Prioridad:** High · **Estimación:** 5

---

### HU-4.2 — TTL y propagación multi-salto

**Tipo:** Historia
**Resumen:** Reenvío con TTL para propagación de varios saltos sin bucles infinitos

**Descripción:**
Como red, quiero que los registros se reenvíen a nuevos vecinos con un TTL decreciente, para alcanzar dispositivos lejanos sin saturar.

**Criterios de aceptación:**
- [ ] TTL decrementa por salto; a 0 deja de propagarse
- [ ] Dedupe evita reenvíos redundantes
- [ ] Demo verificable de 3 saltos (A → B → C) sin internet

**Prioridad:** High · **Estimación:** 5

---

### HU-4.3 — Subida data-mule al backend

**Tipo:** Historia
**Resumen:** Dispositivo con internet sube al backend lo recogido por mesh

**Descripción:**
Como dispositivo que recupera internet, quiero subir al backend los registros de comunidad que recogí por mesh, para que la red se cure.

**Criterios de aceptación:**
- [ ] Al detectar internet, envía registros pendientes a `POST /ingest`
- [ ] Solo sube lo que el backend aún no tiene (dedupe)
- [ ] Reintentos ante fallo de red
- [ ] Marca registros como ya subidos

**Prioridad:** High · **Estimación:** 3

---

### HU-4.4 — Gestión de descubrimiento y energía

**Tipo:** Historia
**Resumen:** Anuncios intermitentes para descubrir vecinos cuidando la batería

**Descripción:**
Como app que corre en gama baja, quiero descubrir vecinos sin agotar la batería, para ser usable durante horas en un desastre.

**Criterios de aceptación:**
- [ ] Anuncio/escaneo en intervalos configurables
- [ ] Conexión solo cuando hay datos nuevos que intercambiar
- [ ] Medición de impacto en batería documentada

**Prioridad:** Medium · **Estimación:** 3

---

# 🟨 EPIC-5 — Demo de desastre

> **Objetivo:** Demostrar el sistema end-to-end en escenarios de red degradada.
> **Etiquetas:** `guacamalla-red`, `demo`

---

### HU-5.1 — Guion y datos de demo

**Tipo:** Historia
**Resumen:** Preparar escenario, canales y datos sembrados para la demostración

**Descripción:**
Como equipo, queremos un guion reproducible con datos realistas, para una demo clara y sin fallos.

**Criterios de aceptación:**
- [ ] Canales sembrados (alertas, refugios, ayuda médica)
- [ ] Guion paso a paso del escenario
- [ ] Dispositivos preparados/etiquetados (A, B, C)

**Prioridad:** Medium · **Estimación:** 2

---

### HU-5.2 — Escenario degradación end-to-end

**Tipo:** Historia
**Resumen:** Demostrar internet → SMS → mesh → dispositivo aislado

**Descripción:**
Como presentador, quiero mostrar cómo la información sigue fluyendo a medida que cae la infraestructura, para evidenciar el valor del sistema.

**Criterios de aceptación:**
- [ ] Con internet: todo fluye en tiempo real
- [ ] Sin internet, con celular: SMS entrega titulares críticos
- [ ] Sin celular: mesh propaga entre teléfonos
- [ ] Dispositivo aislado recibe datos vía mesh de un vecino
- [ ] Data-mule: un device sube reportes al recuperar internet

**Prioridad:** Medium · **Estimación:** 3

---

# 🟩 EPIC-6 — Infra & soporte

> **Objetivo:** Monorepo, paquete compartido y tooling base.
> **Etiquetas:** `guacamalla-red`, `infra`

---

### HU-6.1 — Monorepo y workspace

**Tipo:** Historia
**Resumen:** Estructura de monorepo (`backend`, `app`, `packages/shared`)

**Descripción:**
Como equipo, queremos un monorepo con workspaces, para compartir el modelo de datos y agilizar el desarrollo.

**Criterios de aceptación:**
- [ ] Workspace raíz con `backend/`, `app/`, `packages/shared/`
- [ ] TypeScript configurado de forma consistente
- [ ] `packages/shared` consumible desde backend y app
- [ ] README con instrucciones de arranque

**Prioridad:** High · **Estimación:** 2

---

### HU-6.2 — Scripts de arranque y entorno

**Tipo:** Historia
**Resumen:** Scripts para levantar backend y app en desarrollo

**Descripción:**
Como desarrollador, quiero comandos simples para correr todo, para iterar rápido en el hackathon.

**Criterios de aceptación:**
- [ ] Script para levantar backend (con datos de ejemplo)
- [ ] Script/instrucciones para correr la app en Android
- [ ] Variables de entorno documentadas (URLs, llaves, número SMS)

**Prioridad:** Medium · **Estimación:** 1

---

## 📌 Notas de uso en Jira
- Cada **EPIC-x** es una Épica; cada **HU-x.y** es una Historia bajo esa épica.
- Los **criterios de aceptación** (checkboxes) pueden ir en el campo de descripción o convertirse en sub-tareas.
- Las estimaciones están en **story points** (escala Fibonacci); ajústalas a la velocidad real del equipo.
- Orden recomendado de ejecución: **EPIC-0 → EPIC-6 → EPIC-1 → EPIC-2 → EPIC-3 / EPIC-4 → EPIC-5.**
