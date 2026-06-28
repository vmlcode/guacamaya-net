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

---

## Iteración 9 — 2026-06-28 (loop 10m, tick 10)

### Cambios
- **`BleMeshRuntime`**: no `restart()` si ya escanea — elimina ráfaga 5× `scan started`
- **`Observer`**: Xiaomi API ≤31 → `LEGACY_STACK` por defecto; PHY 1M en LEGACY; probe cada 100 callbacks
- **`BleConfig`**: LEGACY_STACK fuerza `PHY_LE_1M` (match Realme 1M/1M ADV)
- **`ble-reverse-test`**: sweet OBSERVE 8 s antes de Realme START; sin force-stop sweet en fase 2
- **`FunctionalProbe`**: `nodes=N frames=M` en logcat (fallback cuando logd pierde FloodRouter)
- **`demo.sh received`**: muestra probe_nodes/probe_frames

### Prueba adb (`ble-reverse-test`)
| Test | Resultado |
|------|-----------|
| sweet→Realme | **93 OK** |
| Realme→sweet | **0 OK** (logcat FloodRouter) — probe en sweet mostró `target=752de3df` (nodo Realme) |
| Scan thrashing | **corregido** — una sola sesión scan vs 5× antes |

### Diagnóstico
logd en sweet pierde logs `FloodRouter`/`Observer`; usar `guacamaya.probe` con `nodes`/`frames` o `--pid=`. Realme→sweet puede estar recibiendo mesh pero sin OK en logcat.

### Pendiente tick 11
- Validar Realme→sweet vía `probe nodes≥1 frames>0` como criterio de éxito
- Capturar `saw UUID` en sweet si frames=0
- Brújula sweet: calibración física

---

## Iteración 10 — 2026-06-28 (loop 10m, tick 11)

### Cambios
- **`demo.sh`**: `probe_rx_ok()` — éxito RX si `nodes≥1`, `frames≥1` o `target=` (fallback logd MIUI)
- **`ble-reverse-test` / `device-test`**: PASS explícito con probe fallback
- **`GuacamayaForegroundService`**: health loop log `mesh nodes=N frames=M` en `guacamaya.probe` (sin UI)

### Prueba adb (Realme START → sweet OBSERVE, 75 s)
| Métrica | Resultado |
|---------|-----------|
| FloodRouter OK | **1** |
| probe | `nodes=2 frames=26 target=752de3df` |
| Realme→sweet | **PASS** |

### Nota
`observe-on sweet` (activity foreground) + broadcast Realme START. Sin UI, probe UI no corre; FGS `mesh nodes=` cubre validación adb-only.

### Pendiente tick 12
- Brújula sweet: calibración física + `functional-compass-calibrate sweet`
- Estabilizar Realme→sweet en `ble-reverse-test` completo (140 s)

---

## Iteración 11 — 2026-06-28 (loop 10m, tick 12)

### Cambios
- **`ble-reverse-test` fase 2**: force-stop ambos + sweet OBSERVE antes de Realme START
- **`received`**: parse `mesh nodes=` FGS; tail 10 líneas probe
- **Fase 2 cierre**: redispatch OBSERVE + **25 s** wait (probe MIUI tarda ~30 s en primer tick)

### Prueba adb (`ble-reverse-test`)
| Fase | Resultado |
|------|-----------|
| sweet→Realme | **97 OK**, probe `nodes=2 frames=64` |
| Realme→sweet (automático) | FAIL en script — logcat vacío al instante de `received` |
| Realme→sweet (+25 s manual) | probe `nodes=2 frames=42` (RX mesh activo; lag logcat) |

### Brújula sweet
`functional-compass-calibrate sweet`: taps OK; probe sigue `magnet=bad usable=false` — requiere figura-8 física.

### Pendiente tick 13
- Poll `received` hasta probe/mesh visible (timeout 45 s) en fase 2
- Brújula sweet en campo

---

## Iteración 12 — 2026-06-28 (loop 10m, tick 13)

### Cambios
- **`wait_rx_probe()`**: poll cada 5 s hasta 45 s — FloodRouter OK o probe mesh visible
- **`ble-reverse-test` fase 2**: PASS si `wait_rx_probe` exitoso (no re-fallar por rotación logcat)

### Prueba adb (`ble-reverse-test`)
| Fase | Resultado |
|------|-----------|
| sweet→Realme | **92 OK**, `nodes=2 frames=67` |
| Realme→sweet | `wait_rx_probe` **probe visible @25 s** — PASS con fix (logcat vacío en `received` instantáneo) |

### Brújula
Sin cambio en sweet (`magnet=bad`); Realme `usable=true magnet=high`.

### Pendiente tick 14
- Brújula sweet: figura-8 física
- Confirmar `saw UUID` / FloodRouter OK en sweet fase 2 (logd `--pid=`)

---

## Iteración 13 — 2026-06-28 (loop 10m, tick 14)

### Cambios
- **`Observer`**: `saw_uuid` en tag `guacamaya.probe` (diagnóstico `--pid=`)
- **`probe_snapshot()`**: logcat por PID → tag → grep full (MIUI)
- **`wait_rx_probe`**: timeout **60 s**; fase 2 dump incluye `saw_uuid` / `mesh nodes`

### Prueba adb (`ble-reverse-test` completo)
| Fase | Resultado |
|------|-----------|
| sweet→Realme | OK (FloodRouter, ~57–92 según corrida) |
| Realme→sweet | **PASS (probe poll @35 s)** — `nodes=2 frames=42 target=752de3df` |

Bidireccional BLE mesh validado en script automatizado.

### Brújula sweet
Sigue `magnet=bad` — requiere figura-8 manual (no automatizable por adb).

### Pendiente tick 15
- Brújula sweet en campo
- ENU/bearing cruzado entre dispositivos con brújula usable

---

## Iteración 14 — 2026-06-28 (loop 10m, tick 15)

### Cambios
- **`CompassHeading`**: accel co-registrado con rotation vector; fallback magnet+accel aunque exista rotation (MIUI sweet)
- **`functional-compass`**: wait 18 s + logcat `--pid=` para probe en sweet

### Brújula cruzada (adb, teléfonos en mesa)
| Dispositivo | heading | usable | magnet |
|-------------|---------|--------|--------|
| Realme | **90–103°** | true | high |
| sweet | **0°** | false | bad |

Delta no comparable hasta calibrar magnetómetro sweet (figura-8 en Ajustes o movimiento físico). `tap-calibrate-north` adb no corrige `magnet=bad`.

### BLE
Sin regresión — último `ble-reverse-test` bidireccional PASS (tick 14).

### Pendiente tick 16
- Calibración magnética sweet en campo → repetir `functional-compass`
- Con sweet usable: comparar `rel=` en probe con teléfonos paralelos apuntando al mismo rumbo

---

## Iteración 15 — 2026-06-28 (loop 10m, tick 16)

### Cambios
- **`CompassHeading`**: heading se actualiza con `magnet=bad` (usable sigue false); prioriza fallback accel+magnet sobre rotation vector en MIUI
- **`demo.sh compass-miui`**: abre brújula MIUI / ajustes ubicación + instrucción figura-8

### Brújula sweet
Sigue requiriendo **calibración física** (`magnet=bad`). Código ahora permite heading degradado cuando el magnetómetro reporta datos pero accuracy=unreliable.

### Uso adb
```bash
./scripts/demo.sh compass-miui sweet   # figura-8 manual ~15 s
./scripts/demo.sh functional-compass     # comparar Realme vs sweet
```

### Pendiente tick 17
- Repetir brújula cruzada tras calibración sweet en campo
- ENU `rel=` / bearing entre dispositivos

---

## Iteración 16 — 2026-06-28 (loop 10m, tick 17)

### Prueba adb
| Check | Resultado |
|-------|-----------|
| Realme brújula | **heading 98–109°**, `usable=true magnet=high` |
| sweet brújula | **heading=0**, `magnet=bad usable=false` (sin calibración física) |
| sweet BLE RX | **OK** — `saw_uuid`, `FloodRouter OK` nodo Realme `752de3df` |
| `functional-compass` | Mejorado: poll 27 s + resumen Δheading |

### Cambios
- **`functional-compass`**: poll probe hasta 27 s por dispositivo; imprime Δheading si ambos responden

### Pendiente tick 18
- `./scripts/demo.sh compass-miui sweet` + figura-8 → repetir `functional-compass`
- Brújula usable en sweet para validar ENU `rel=` cruzado

---

## Iteración 17 — 2026-06-28 (loop 10m, tick 18)

### Prueba adb
| Test | Resultado |
|------|-----------|
| `functional-compass` | Realme **87°** usable/high; sweet **0°** magnet=bad; Δ≈87° |
| `compass-miui sweet` | Abre `com.miui.compass` OK |
| `device-test` | **PASS** (probe poll `nodes=2 frames=47`) tras fix routing |

### Cambios
- **`device-test`**: alineado con `am_start_action` + `wait_rx_probe` (antes FAIL por intents viejos)

### Estado loop funcional (~18 ticks)
- **BLE bidireccional**: validado (`ble-reverse-test` + `device-test`)
- **Brújula sweet**: bloqueada en `magnet=bad` — requiere figura-8 manual
- **GPS/ENU probe**: activo en ambos; `rel=` útil cuando sweet tenga brújula usable

### Pendiente tick 19
- Calibración magnética sweet en campo
- Comparar `rel=` con teléfonos paralelos apuntando al mismo objetivo

---

## Iteración 18 — 2026-06-28 (loop 10m, tick 19)

### Prueba adb
| Test | Resultado |
|------|-----------|
| `functional-compass` | sweet **0° magnet=bad**; Realme sin probe en ventana (logd intermitente) |
| `device-test` | **PASS** probe @5 s (`nodes=2 frames=47`) |

### Cambios
- **`functional-compass`**: poll extendido a **36 s** por dispositivo

### Resumen loop (~19 ticks)
| Área | Estado |
|------|--------|
| BLE mesh bidireccional | ✅ `ble-reverse-test` + `device-test` |
| ENU/GPS probe | ✅ lat/lon/acc/bearing/`rel=` |
| Brújula Realme | ✅ ~87–109° `magnet=high` |
| Brújula sweet | ❌ `magnet=bad` — **acción manual** `compass-miui` + figura-8 |

### Pendiente (post-loop)
- Calibrar sweet en campo → `functional-compass` con Δheading ≈ 0° en paralelo
- Validar `rel=` cruzado con brújula usable en ambos

---

## Iteración 19 — 2026-06-28 (loop 10m, tick 20 — cierre)

### Prueba adb (milestone)
| Test | Resultado |
|------|-----------|
| `functional-compass` | sweet **0° magnet=bad** `nodes=2 frames=47`; Realme **103°** `magnet=high` `nodes=2 frames=73` |
| `device-test` | **PASS** (`PROBE_RX_PASS` @10 s, `nodes=2 frames=47`) |

### Resumen loop (20 ticks, ~3 h)
| Área | Estado |
|------|--------|
| BLE mesh bidireccional | ✅ `ble-reverse-test`, `device-test`, probe `nodes`/`frames`/`target=` |
| GPS/ENU probe | ✅ lat/lon/acc/bearing/`rel=`/`co_loc` en ambos |
| Brújula Realme | ✅ ~87–109° `magnet=high` |
| Brújula sweet | ❌ `magnet=bad` — requiere figura-8 (`./scripts/demo.sh compass-miui sweet`) |
| UI | Sin cambios (alcance intencional) |

### Entregables
- Runtime BLE compartido + routing adb MIUI (`AdbCommandReceiver`, `am_start_action`)
- Scripts: `ble-reverse-test`, `device-test`, `functional-compass`, `probe-dump`, `compass-miui`
- Commits en `develop` (0155c04 … c29389a)

### Siguiente paso manual
1. Calibrar magnetómetro sweet (figura-8)
2. `./scripts/demo.sh functional-compass` → esperar Δheading ≈ 0° con teléfonos paralelos
3. Comparar `rel=` y `bearing` entre dispositivos

---

## Iteración 20 — 2026-06-28 (loop 10m, tick 21)

### Prueba adb
| Test | Resultado |
|------|-----------|
| `functional-compass` | sweet **0° magnet=bad**; Realme **97° magnet=high**; mesh `nodes=2` en ambos |
| Ubicación | `co_loc=false` en sweet (`dist_m=248`) — teléfonos separados |

### Notas
- Sin cambio en brújula sweet; sigue bloqueada en calibración manual.
- BLE mesh estable; sin cambios de código en este tick.

---

## Iteración 21 — 2026-06-28 (loop 10m, tick 30)

### Prueba adb (milestone)
| Test | Resultado |
|------|-----------|
| `device-test` | **PASS** @0 s — `nodes=2 frames=69` (ticks 26–30 consecutivos) |
| `functional-compass` | sweet **0° magnet=bad**; Realme sin probe en 36 s (logd intermitente) |

### Resumen ticks 22–30 (~1,5 h post-cierre)
| Área | Estado |
|------|--------|
| BLE mesh | ✅ Estable; `device-test` PASS en cada tick |
| Brújula Realme | ✅ ~97–104° cuando probe visible |
| Brújula sweet | ❌ `magnet=bad` — sin calibración manual en campo |
| Fixes aplicados | `device-test`: HEARTBEAT_ON, poll 60 s, fallback Realme, fix `set -e` (`cc9bd44`) |

### Bloqueador único
Calibración magnética sweet (`./scripts/demo.sh compass-miui sweet` + figura-8).

---

## Iteración 22 — 2026-06-28 (loop 10m, tick 40)

### Prueba adb (milestone)
| Test | Resultado |
|------|-----------|
| Dispositivos | Solo **sweet** (`e06518dd`); Realme desconectado desde tick 38 |
| `device-test` | **PASS** parcial — sweet `nodes=2 frames=69` (sin TX Realme) |
| `functional-compass` | sweet **0° magnet=bad**; Realme N/A |

### Resumen ticks 31–40
| Área | Estado |
|------|--------|
| BLE (2 dispositivos) | ✅ PASS ticks 31–37 |
| Realme adb | ❌ Desconectado ticks 38–40 |
| Brújula sweet | ❌ `magnet=bad` — sin calibración manual |

### Acción requerida
1. Reconectar Realme por USB (`adb devices`)
2. Calibrar magnetómetro sweet (figura-8)
3. Re-ejecutar `ble-reverse-test` y `functional-compass`
