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
- [ ] Calibración magnética / aviso si `accuracy` baja.
- [ ] Suavizado RSSI para distancia aproximada en radar.
- [ ] Scan watchdog más agresivo en sweet si callbacks < 1/min.
- [ ] Reducir recomposiciones Compose en lista de mensajes.
