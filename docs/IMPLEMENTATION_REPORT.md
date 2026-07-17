# Reporte tecnico de implementacion

Fecha: 2026-07-09

## Alcance y fuente

Este reporte describe el arbol de trabajo `guacamaya-net-develop-latest`, basado en
la rama remota `develop` de `vmlcode/guacamaya-net`. Incluye los cambios locales no
commitados de control, seguridad, flooding y Sonar Fase 1. No describe la copia
antigua fuera de este arbol ni revierte cambios existentes en SOSNet.

## Stack y limites de responsabilidad

| Capa | Implementacion | Responsabilidad |
| --- | --- | --- |
| Android | Kotlin, Compose, Room, FGS, Bluetooth LE, Wi-Fi Aware | Mesh hot path y radio viven nativamente; la UI solo observa estado. |
| Mesh | `Observer`, `FloodRouter`, `MessageStore`, `Broadcaster` | Recepcion, cascada criptografica, dedupe, persistencia y relay BLE. |
| Protocolo | Frame BLE v1 de 119 B | TTL mutable + payload firmado de 22 B + pubkey Ed25519 + firma. |
| Backend | Fastify, canales, ingest, WebSocket | Verifica ingest, persiste, emite alertas/certificados/recibos. |
| Compartido | TypeScript, noble Ed25519 | Tipos, bytes canonicos, verificacion de receipts y certificados. |

La aplicacion movil de este arbol es Android nativo; no depende de Expo Go ni de un
runtime JavaScript en el camino de retransmision.

## Implementado

### Identidad, control y continuidad

- Los `ControlReceipt` distinguen `delivery`, `soft-resolve`, `backend-resolve` y
  `self-cancel`.
- Un `soft-resolve` certificado solo cambia el estado visual; no apaga el SOS de la
  victima. Solo `self-cancel` de la victima y `backend-resolve` pueden detenerlo.
- El backend emite certificados de rescatista de hasta siete dias y puede renovarlos
  en la respuesta de `/ingest` para que una mula los distribuya offline.
- La firma del certificado se valida respecto a `issuedAt` del receipt, no al reloj de
  recepcion. Esto evita invalidar un receipt legitimo que viajo dias por la malla.
- Los receipts tienen ventana de replay y dedupe en memoria compartida. El `msgId` de
  SOS se genera por sesion con CSPRNG para evitar colision con un SOS futuro.
- Produccion exige `BACKEND_PRIVATE_KEY_HEX` persistente. Android ancla
  `BACKEND_PUBLIC_KEY_HEX` en el build y no sustituye esa ancla por TOFU de `/pubkey`.
- `ChannelRecord.prevHash` permite una hash-chain por autor: detecta huecos/forks sin
  cadena global, consenso, mineria ni proof-of-work.

### Frame y flooding

- La cascada en `FloodRouter` valida enlace pubkey/nodeId, CRC, ventana temporal y
  firma Ed25519 antes de persistir o retransmitir.
- El flooding aplica token bucket por pubkey, suppression por contador y retardo
  inverso a RSSI para favorecer avance geografico.
- El servicio de victima usa `MeshClock` para timestamps, `msgId` aleatorio por
  sesion y degradacion de cadencia por edad/bateria.
- Existe UUID BLE reservado para un futuro frame v2 incompatible. Los nodos nuevos
  deberan escuchar v1 y v2; no se reutilizara el UUID v1 para un formato incompatible.

### Sonar Fase 1

- `NeighborObservation` y `NeighborEstimate` viven en Room separados de los SOS
  firmados. Guardan nodeId, `MeshClock`, RSSI crudo, fuente, PHY, potencia TX cuando
  Android la expone y posicion reciente del observador.
- El filtro mantiene hasta 50 muestras en RAM por vecino; usa mediana temporal de
  hasta 9 muestras en 8 s, minimo 3, y EWMA sobre dBm. Tras silencio prolongado se
  reinicia la estimacion de rango, no el liveness.
- `lastSeenAt` y el estado estable se persisten. Tras reinicio, el timer vuelve a
  calcular `HEARD`, `DEGRADED` y `LOST` desde Room: 15 s para degradado y 50 s para
  perdido. Aun no se publica `NODE_LOST` al backend.
- Solo se registra RSSI cuando el TTL observado coincide con el TTL de origen. Como
  ese byte es mutable y no firmado, el resultado se etiqueta siempre como **directa
  no verificada**: nunca autentica proximidad.
- El radar cruza RSSI con la distancia GPS del mismo frame firmado usando discrepancia
  de `3 * sqrt(sigma_gps^2 + sigma_rssi^2)`. Si no son consistentes, conserva visibles
  ambas evidencias y advierte al rescatista; no usa RSSI para desplazar el bearing GPS.
- Los perfiles de path-loss distinguen 1M, 2M y Coded por `n` y offset provisionales.
  Son parametros iniciales, no calibracion de campo.
- El radar usa scan balanced normalmente y low-latency solo durante busqueda. Al salir
  conserva low-latency 10 s para no exceder el rate-limit Android de start/stop scan.
- El bearing corporal requiere ocho muestras, cuatro sectores de 45 grados y 6 dB de
  contraste; pide giro de 15-20 s o dos vueltas. No implementa AoA/AoD.

## Garantias y no-garantias

| Propiedad | Estado |
| --- | --- |
| Identidad/origen del SOS | Ed25519 validado. |
| Integridad del payload y coordenadas que contiene | Firmada por el origen. |
| Proximidad fisica por RSSI | No autenticada; solo guia visual con incertidumbre. |
| TTL directo | Heuristica falsificable; puede existir wormhole/relay sin decremento. |
| Distancia RTT | No implementada aun. |
| Soft-resolve de tercero detiene victima | Prohibido por el modelo de control. |
| Receipt recibido por BLE/NAN en campo | No implementado aun. |

## Deuda critica antes de piloto

1. El endpoint legado `/resolve` basado en quorum todavia puede limpiar un SOS y no
   cumple la semantica de soft-resolve. Debe restringirse, migrarse o deshabilitarse.
2. Falta CRL firmada/distribuida por mula para certificados de rescatista.
3. Falta LRU persistente movil para replay de control; el dedupe actual no sobrevive
   reinicio.
4. Falta codec y transporte BLE para `self-cancel`, `backend-resolve` y `delivery`.
   NAN debe llevar certificados, soft-resolve y digest, pero BLE es el fallback
   garantizado para dispositivos sin Wi-Fi Aware.
5. `MeshClock` se usa al emitir SOS; falta aplicar su confianza/offset a toda
   validacion Android de receipts y definir la UI de tiempo no verificado.
6. NAN actual no tiene rafagas duty-cycled, RTT, anti-entropia, digest persistente ni
   intercambio de observaciones. Nearby Connections tampoco esta integrado.

## Calibracion: pendientes definidos, no implementados

### Logger Kotlin de campo

Debe capturar cada `ScanResult` crudo en CSV, sin dedupe de advertising. Esquema
minimo:

```text
session_id,wall_time_ms,mesh_time_ms,distance_m,environment,
transmitter_model,observer_model,node_id,rssi_dbm,tx_power_dbm,
primary_phy,secondary_phy,is_legacy,advertising_sid,frame_ttl
```

La sesion debe declarar distancia real, entorno (`open`, `body`, `wall`, `rubble`),
modelo emisor/observador y orientacion. El logger no debe entrar al hot path de relay:
usa buffer acotado y escritura batch. Requiere consentimiento visible porque registra
identificadores de dispositivo/sesion.

### Ajuste robusto y tabla de perfiles

Falta un script que lea esos CSV, agrupe por par emisor-observador + PHY + entorno,
ajuste `P0` y `n` del modelo `RSSI = P0 - 10*n*log10(d)` con perdida Huber y emita:

```json
{
  "transmitterModel|observerModel|secondaryPhy": {
    "p0Dbm": -59.0,
    "pathLossExponent": 2.7,
    "residualSigmaDb": 7.2,
    "distanceUncertaintyFactor": 0.85,
    "samples": 120
  }
}
```

`distanceUncertaintyFactor` debe derivarse de la desviacion residual del ajuste y
alimentar el anillo del radar. No se deben promocionar los perfiles provisionales a
operativos sin pruebas a 1, 5, 10, 25 y 50 m, primero a cielo abierto y luego con
obstaculo corporal/estructural.

## Verificacion ejecutada

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:testDebugUnitTest`: correcto.
- `bun test`: 33 pruebas correctas, 0 fallos.
- `git diff --check`: sin errores de espacios.

No se ha ejecutado validacion RF, ranging NAN ni presupuesto de bateria de 24 h sobre
hardware real. Esa medicion sigue siendo requisito de salida para piloto.
