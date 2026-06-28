# Loop de mejora continua — Guacamaya-net

Registro de cada iteración del loop `/loop 10m`. Repo: [guacamaya-net/develop](https://github.com/vmlcode/guacamaya-net/tree/develop).

---

## Iteración 1 — 2026-06-28

### Objetivos
- Corregir brújula inconsistente entre dispositivos (sweet vs Realme: sur/norte invertido).
- Mapa sin tiles descargados: cuadrícula local con posición del usuario y nodos.
- Optimización RAM / dispositivos viejos.
- Mejora TX/RX incremental.

### Cambios

| Área | Cambio |
|------|--------|
| **Brújula** | Nuevo `CompassHeading.kt`: remapeo por rotación de pantalla (`remapCoordinateSystem`), filtro exponencial, fallback acelerómetro+magnetómetro para MTK/Xiaomi. Rosa del radar rota con norte geográfico. |
| **Radar** | `CompassMath.relativeBearing()` evita error de wrap en ±180°. |
| **Mapa** | `GridMap.kt`: cuadrícula offline en metros (este/norte), usuario en centro, última posición por nodo. Indicador norte sincronizado con brújula. |
| **RAM** | Eliminada dependencia **osmdroid** (~tile cache, WebView overhead). |
| **TX/RX** | `FloodRouter`: reutiliza instancia `MessageDigest` (un hilo consumidor). |

### Archivos nuevos
- `android/app/src/main/kotlin/net/guacamaya/ui/CompassHeading.kt`
- `android/app/src/main/kotlin/net/guacamaya/ui/GeoGrid.kt`
- `android/app/src/main/kotlin/net/guacamaya/ui/GridMap.kt`
- `android/app/src/test/kotlin/net/guacamaya/ui/CompassMathTest.kt`

### Verificación pendiente (dispositivos)
```bash
cd android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:assembleDebug
./scripts/demo.sh observe-on sweet
./scripts/demo.sh heartbeat-on <SERIAL_REALME>
# En ambos: abrir Radar y comparar heading° con brújula del sistema
# Mapa: Ver mapa → punto verde = tú, puntos azul/rojo = nodos
```

### Próxima iteración (backlog)
- [x] Calibración magnética / botón «Calibrar norte» en radar
- [ ] Suavizado RSSI para distancia aproximada en radar
- [ ] Scan watchdog más agresivo en sweet si callbacks < 1/min
- [ ] Reducir recomposiciones Compose en lista de mensajes

---

## Iteración 2 — 2026-06-28

### Problema reportado
- Distancia saltaba 1 m, 3 m, 4 m con teléfonos separados ~4 cm.
- Brújula: Realme norte / sweet sur (180° invertido).

### Cambios

| Área | Cambio |
|------|--------|
| **Brújula** | Ejes para teléfono **vertical** (`AXIS_X` + `AXIS_MINUS_Y`). `GEOMAGNETIC_ROTATION_VECTOR`. Botón **Calibrar norte** con offset persistido por dispositivo. |
| **Distancia** | `GeoProximity.kt`: suavizado EMA GPS + posición por nodo; dentro de incertidumbre → **«junto»**; sub-10 m en cm. |
| **Radar** | Flecha atenuada cuando `coLocated`; aviso «GPS no distingue cm». |
| **Mapa** | Posiciones suavizadas compartidas; cuadrícula mínima 2 m / paso 1 m. |

### Verificación
1. Teléfonos juntos → radar **junto** (no 1–4 m).
2. Radar → apuntar top al norte → **Calibrar norte** en ambos → brújula ≈ 0°.

---

## Iteración 3 — 2026-06-28 (loop 10 m)

### Cambios

| Área | Cambio |
|------|--------|
| **RSSI** | Suavizado EMA por nodo; cuando GPS dice «junto» muestra hint BLE (`tocando`, `~1 m`, …). Elige nodo por RSSI si todos co-located. |
| **BLE sweet** | Watchdog legacy: revisa cada **30 s**, reinicia scan si **60 s** sin frame Guacamalla (antes 3 min genérico). |
| **UI** | Lista del mapa usa `MessageListItem` + `contentType` → menos recomposiciones en listas largas. |

### Backlog
- [x] Suavizado RSSI
- [x] Watchdog agresivo sweet
- [x] Recomposiciones lista mensajes
- [ ] Auto-calibración brújula cruzada entre dos nodos visibles
