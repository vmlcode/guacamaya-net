# Loop funcional (10 min) — brújula, posición, plano cartesiano

**Regla:** no mejorar UI; solo funcionalidad y precisión. Validar con `adb` + `guacamaya.probe`.

## Iteración 1 — 2026-06-28

### Cambios
- **ENU cartesiano** (`CartesianGeo.kt`): offsets east/north vía `Location.distanceBetween` (bearing+distance), más preciso a corta distancia que delta lat/lon.
- **GPS** (`LocationTracker.kt`): rechazo de outliers, `setWaitForAccurateLocation`, intervalos más rápidos en alta precisión.
- **Brújula** (`CompassHeading.kt`): remapeo portrait, gate de inclinación (pitch/roll), suavizado adaptativo según accuracy del magnetómetro.
- **Mapa/plano** (`GridMap.kt`): cuadrícula ENU rotada con `-heading` (norte geográfico alineado con brújula); círculo de incertidumbre GPS en origen; escala `fitScaleMeters` incluye `accuracy`.
- **Probe adb** (`FunctionalProbe.kt`): log cada 2 s tag `guacamaya.probe` (heading, lat, lon, acc_m).
- **demo.sh**: `functional-test`, `tap-*`, `probe-dump` para interacción sin tocar pantalla.

### Prueba adb (sweet e06518dd)
```bash
cd android
./scripts/demo.sh functional-test sweet
./scripts/demo.sh probe-dump sweet
```

Resultado iteración 1:
```
guacamaya.probe: heading=0 lat=16.74… lon=-92.62… acc_m=26 speed=0.17 target=da70d303 dist_m=0 bearing=50 rel=0 co_loc=true
```
- Probe activo con app en foreground (sin depender del estado UI «running»).
- `co_loc=true` con ±26 m GPS — distancia «junto» coherente.
- Taps adb abren radar (calibrar norte) y mapa cartesiano.

### Pendiente siguiente tick
- Validar brújula entre sweet y Realme tras calibración.
- BLE asimétrico Realme→sweet (0 OK).
- Probe: log bearing relativo al nodo más cercano (sin UI).

---

## Iteración 2 — 2026-06-28 (loop 10m, tick 1)

### Cambios
- **`CompassState`** + `rememberCompassState()`: expone pitch, roll, `usable`, accuracy magnética sin UI.
- **Probe ampliado**: `pitch`, `roll`, `usable`, `magnet=high|med|low|bad` en `guacamaya.probe`.
- **demo.sh**: `functional-compass` (ambos teléfonos), `ble-reverse-test` (TX/RX en ambas direcciones).

### Prueba adb
| Test | Resultado |
|------|-----------|
| Realme probe | `heading=61 pitch=-11 roll=5 usable=true magnet=high` |
| sweet probe | Sin fix GPS al arrancar en background; en foreground `usable=false magnet=bad` (calibrar / permisos) |
| sweet TX → Realme RX | **26 OK** |
| Realme TX → sweet RX | **0 OK** (asimetría persiste) |

### Pendiente tick 2
- BLE: diagnosticar por qué sweet no recibe frames Realme (Observer LEGACY_STACK vs ADV Realme).
- sweet: calibración magnética + probe con app en foreground ≥10 s.
- Brújula cruzada: comparar `heading` de ambos con teléfonos paralelos.

---

## Iteración 3 — 2026-06-28 (loop 10m, tick 2)

### Diagnóstico BLE Realme→sweet
| Hallazgo | Detalle |
|----------|---------|
| Realme `swap()` | `DeadObjectException` en heartbeat — ADV muerto; **fix:** restart en `Broadcaster.swap()` |
| Scan sweet | `MATCH_NUM_ONE` en LEGACY bloqueaba otros emisores → **MAX_ADVERTISEMENT** |
| Perfil scan | sweet ahora **AGGRESSIVE** primero; fallback LEGACY vía watchdog |
| adb MIUI | `am start -n` + `OBSERVE_ON` separado **no arranca observer**; usar un solo intent con action |
| Realme→sweet | Sigue **0 OK** en test automático; observer a veces no escanea (FGS sin scan tras cold start lento) |

### Cambios
- `Broadcaster.kt`: recuperación en `swap()` tras `DeadObjectException`
- `BleConfig.kt` + `Observer.kt`: scan AGGRESSIVE default, LEGACY alternado
- `GuacamayaForegroundService`: retry observe 2.5 s, log `onStartCommand`
- `FunctionalProbe.kt`: snapshot (no reinicia cada sensor tick)
- `demo.sh`: intents directos, force-stop sweet fase 2, waits 70 s

### Pendiente tick 3
- Confirmar Realme→sweet con intent único + retry observe
- Brújula sweet: `magnet=bad` — calibración en campo
- Comparar headings paralelos sweet vs Realme

---

## Iteración 4 — 2026-06-28 (loop 10m, ticks 3–5)

### Cambios
- **FGS observe persistente**: `wantObserving` en prefs + health loop 8 s + retry BT off
- **MainActivity.onResume**: re-dispatch adb intents (MIUI mata activity antes de FGS foreground)
- **Brújula**: magnetómetro siempre registrado → accuracy en probe
- **demo.sh**: `am start -W`, WAKEUP, Realme TX con `START` (SOS continuo)

### Prueba adb
| Test | Resultado |
|------|-----------|
| sweet→Realme | **89 OK** |
| Realme→sweet | **0 OK** — FGS `createdFromFg=false` en MIUI; observer no arranca desde adb background |
| Realme brújula | `heading=38 usable=true magnet=high` |
| sweet brújula | sin probe (activity en background tras HEARTBEAT intent) |

### Causa raíz probable
MIUI/API 30 inicia FGS desde shell en background → scan BLE no arranca. Mitigación: `onResume` + `-W` + WAKEUP en scripts.

### Pendiente tick 6
- Realme→sweet: validar tras `onResume` fix con teléfono despierto en foreground
- sweet brújula: figura-8 / `tap-calibrate-north` vía adb

---

## Iteración 5 — 2026-06-28 (loop 10m, tick 6)

### Cambios
- **demo.sh `am_start_action`**: `timeout 12` en `-W` (evita hang MIUI — task 892594)
- **`kickObserve()`**: MainActivity.onResume fuerza scan BLE con activity foreground
- **`armAdbSession`**: FLAG_KEEP_SCREEN_ON en intents adb
- **`functional-compass-calibrate`**: taps radar + calibrar norte en sweet

### Prueba adb
| Test | Resultado |
|------|-----------|
| Realme→sweet (40s, kickObserve) | **0 OK** — `allowWhileInUsePermissionInFgs=false`, probe sin `observing` |
| sweet probe | `magnet=bad usable=false` — sensores sin calibrar |

### Nota task 892594
`am start -W` en sweet colgó >90 s → reemplazado por `timeout 12` + fallback sin `-W`.

### Pendiente tick 7
- Realme→sweet: probar con pantalla sweet encendida manualmente (app visible ≥60 s)
- `./scripts/demo.sh functional-compass-calibrate sweet`

---

## Iteración 6 — 2026-06-28 (loop 10m, tick 7)

### Prueba adb (sweet foreground ≥70 s)
| Métrica | Resultado |
|---------|-----------|
| `createdFromFg` | **true** (re-dispatch OBSERVE cada 10 s + taps) |
| GPS sweet | `acc_m=71–80`, `dist_m=125–149` hacia Realme |
| Realme→sweet RX | **0 OK** (sin logs Observer en logcat) |
| Brújula sweet | `magnet=bad heading=0 usable=false` |

### Cambios
- `ble-reverse-test`: re-dispatch OBSERVE ×7 cada 10 s en fase Realme→sweet
- `functional-compass-calibrate`: force-stop + `am_start_action` + más wait
- Probe logs en `kickObserve` / `startObserving` para depuración

### Pendiente tick 8
- Confirmar `kickObserve`/`scan started` en logcat tras install
- Calibrar brújula sweet (figura-8 física + `tap-calibrate-north`)
- Si scan arranca pero 0 OK: PHY Realme 1M/1M vs sweet AGGRESSIVE

---

## Iteración 7 — 2026-06-28 (loop 10m, tick 8)

### Cambios
- **`BleMeshRuntime.kt`**: singleton Observer + FloodRouter; scan desde activity foreground y FGS
- **`GuacamayaForegroundService`**: delega observe a BleMeshRuntime; restaurado `scheduleObserveRetry()`
- **`MainActivity`**: `setIntent()` en `onNewIntent` (fix `singleTop`); BLE en `dispatchServiceAction`; `routeAdbIntent` + retry 800 ms
- **`demo.sh`**: `am start -f 0x14008000` (CLEAR_TASK) — elimina stack MIUI PowerDetailActivity que tragaba intents adb

### Diagnóstico tick 8
| Hallazgo | Detalle |
|----------|---------|
| Task MIUI | Task #107 acumulaba 5× `PowerDetailActivity` encima de MainActivity → `onNewIntent` no llegaba |
| `singleTop` | Sin `setIntent()`, `getIntent().action` seguía siendo `MAIN` tras redispatch adb |
| Realme→sweet | Sigue **0 OK** tras BleMeshRuntime + CLEAR_TASK |
| sweet→Realme | **1 OK** (test corto; antes ~80–90) |
| Brújula sweet | `magnet=bad heading=0 usable=false` |

### Prueba adb
```bash
cd android
./scripts/demo.sh ble-reverse-test
adb -s e06518dd logcat -d -s guacamaya.probe:I guacamaya.ble.Observer:I
```

### Pendiente tick 9
- Confirmar `dispatchServiceAction` / `BleMeshRuntime scanning=true` en logcat (MIUI puede filtrar o retrasar FGS)
- Realme→sweet: mantener sweet en foreground + permisos ubicación/BT concedidos manualmente
- Brújula sweet: calibración física + `functional-compass-calibrate sweet`
- Si scan activo pero 0 OK: alinear PHY scan Realme (1M coded) vs sweet AGGRESSIVE

---

## Iteración 8 — 2026-06-28 (loop 10m, tick 9)

### Cambios
- **`AdbCommandReceiver`**: adb vía `am broadcast` — evita MainActivity/singleTop/MIUI task stacks
- **`MainActivity`**: extra `guacamaya_adb_action` + prefs + retry permisos; logs `routeAdbIntent`
- **`demo.sh`**: broadcast antes de `am start`; extras en todos los intents adb
- **`Observer`**: log `SecurityException` si scan denegado

### Prueba adb (`ble-reverse-test`)
| Test | Resultado |
|------|-----------|
| sweet→Realme | **0 OK** |
| Realme→sweet | **0 OK** |
| Scan sweet | **confirmado** — `guacamaya.ble.Observer: scan started profile=AGGRESSIVE` (×5 en logcat) |
| logd MIUI | Inestable en sweet (`logcat: Unexpected EOF`) — usar `--pid=` o broadcast |

### Hallazgo clave
El broadcast receiver **sí arranca el scan** en sweet; el bloqueo anterior era routing adb → MainActivity, no PHY. Con scan activo y 0 OK: investigar frames Realme (extended ADV / service-data en stack sweet).

### Pendiente tick 10
- Realme→sweet con scan confirmado: capturar `saw UUID` / `FloodRouter: OK` en logcat PID
- Evitar restart scan en ráfaga (5× `scan started` simultáneos)
- Brújula sweet: calibración física
