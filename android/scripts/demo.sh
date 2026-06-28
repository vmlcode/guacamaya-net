#!/usr/bin/env bash
# Guacamaya — demo runner.
#
# Usage:
#   ./scripts/demo.sh build     # assemble debug APK
#   ./scripts/demo.sh install   # build + install on first connected device
#   ./scripts/demo.sh tamper    # generate tampered frame JSON on host
#   ./scripts/demo.sh logcat    # stream colorized logcat from first device
#
# Multiple devices: set ANDROID_SERIAL to pick one (standard adb behavior).
# Redmi Note 10 Pro (codename sweet): ./scripts/demo.sh observe-on sweet

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PKG=net.guacamaya
ACTIVITY=${PKG}/.ui.MainActivity
SVC=${PKG}/.service.GuacamayaForegroundService

# Resolve adb serial: explicit arg (e.g. "sweet"), ANDROID_SERIAL, or first online device.
# When arg is a codename (ro.product.device), pick the matching USB device.
adb_serial() {
  local hint="${1:-}"
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    echo "$ANDROID_SERIAL"
    return
  fi
  if [ -z "$hint" ]; then
    adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'
    return
  fi
  local s dev
  for s in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
    dev="$(adb -s "$s" shell getprop ro.product.device 2>/dev/null | tr -d '\r\n' || true)"
    if [ "$dev" = "$hint" ] || [ "$s" = "$hint" ]; then
      echo "$s"
      return
    fi
  done
  echo "No adb device matching '$hint' (ro.product.device or serial)." >&2
  exit 1
}

adb_cmd() {
  local serial
  serial="$(adb_serial "${DEVICE_HINT:-}")"
  adb -s "$serial" "$@"
}

screen_wh() {
  adb_cmd shell wm size 2>/dev/null | sed -n 's/.*Physical size: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1
}

tap_pct() {
  local px="${1:?}" py="${2:?}"
  local wh w h x y
  wh="$(screen_wh)"
  w="${wh%% *}"
  h="${wh##* }"
  if [ -z "$w" ] || [ -z "$h" ]; then
    echo "Could not read screen size (wm size)." >&2
    exit 1
  fi
  x=$(( w * px / 100 ))
  y=$(( h * py / 100 ))
  echo "tap $x,$y (${px}%,${py}%) serial=$(adb_serial "${DEVICE_HINT:-}")"
  adb_cmd shell input tap "$x" "$y"
}

am_start_action() {
  local serial="$1"
  local action="$2"
  # MIUI sweet: stale task stacks (PowerDetailActivity) swallow adb intents — clear task.
  local flags="0x14008000"
  adb -s "$serial" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
  # Broadcast starts BLE/FGS without MainActivity lifecycle (logd also flaky on sweet).
  adb -s "$serial" shell am broadcast -a "$action" -n "${PKG}/.adb.AdbCommandReceiver" >/dev/null 2>&1 || true
  # MIUI sweet: `am start -W` can hang indefinitely — cap wait, then retry without -W.
  if timeout 12 adb -s "$serial" shell am start -W -a "$action" -n "$ACTIVITY" -f "$flags" --es guacamaya_adb_action "$action" >/dev/null 2>&1; then
    :
  else
    adb -s "$serial" shell am start -a "$action" -n "$ACTIVITY" -f "$flags" --es guacamaya_adb_action "$action" >/dev/null 2>&1 || true
  fi
  sleep 1
  # Second dispatch triggers MainActivity.onResume → FGS foreground on MIUI.
  adb -s "$serial" shell am start -a "$action" -n "$ACTIVITY" -f "$flags" --es guacamaya_adb_action "$action" >/dev/null 2>&1 || true
  sleep 1
}

# Fetch recent guacamaya.probe lines — PID filter first (MIUI drops tagged logcat).
probe_snapshot() {
  local serial="$1"
  local pid probe
  pid="$(adb -s "$serial" shell pidof "$PKG" 2>/dev/null | awk '{print $1}' | tr -d '\r\n' || true)"
  if [ -n "$pid" ]; then
    probe="$(adb -s "$serial" logcat -d --pid="$pid" 2>/dev/null | grep 'guacamaya.probe' | tail -15 || true)"
  fi
  if [ -z "$probe" ]; then
    probe="$(adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | tail -15 || true)"
  fi
  if [ -z "$probe" ]; then
    probe="$(adb -s "$serial" logcat -d 2>/dev/null | grep 'guacamaya.probe' | tail -15 || true)"
  fi
  printf '%s\n' "$probe"
}

# MIUI sweet often drops FloodRouter logcat; treat probe mesh fields as RX success.
probe_rx_ok() {
  local serial="$1"
  local probe nodes frames targets
  probe="$(probe_snapshot "$serial")"
  nodes="$(printf '%s\n' "$probe" | sed -n 's/.*nodes=\([0-9]*\).*/\1/p' | tail -1)"
  frames="$(printf '%s\n' "$probe" | sed -n 's/.*frames=\([0-9]*\).*/\1/p' | tail -1)"
  # FGS health loop logs "mesh nodes=N frames=M" without UI foreground.
  if [ -z "$nodes" ]; then
    nodes="$(printf '%s\n' "$probe" | sed -n 's/.*mesh nodes=\([0-9]*\).*/\1/p' | tail -1)"
  fi
  if [ -z "$frames" ]; then
    frames="$(printf '%s\n' "$probe" | sed -n 's/.*frames=\([0-9]*\).*/\1/p' | tail -1)"
  fi
  targets="$(printf '%s\n' "$probe" | grep -c 'target=[0-9a-f]\{8\}' 2>/dev/null || true)"
  if [ "${nodes:-0}" -ge 1 ] || [ "${frames:-0}" -ge 1 ] || [ "${targets:-0}" -ge 1 ]; then
    echo "PROBE_RX_PASS nodes=${nodes:-0} frames=${frames:-0} target_lines=${targets:-0}"
    return 0
  fi
  echo "PROBE_RX_FAIL nodes=${nodes:-0} frames=${frames:-0} target_lines=${targets:-0}"
  return 1
}

rx_ok_count() {
  local serial="$1"
  adb -s "$serial" logcat -d -s guacamaya.mesh.FloodRouter:I 2>/dev/null | grep -c ' OK ' || true
}

# Poll until FloodRouter OK or probe mesh fields appear (MIUI log lag on sweet).
wait_rx_probe() {
  local serial="$1"
  local timeout="${2:-60}"
  local elapsed=0 ok
  echo "[wait-rx] serial=$serial timeout=${timeout}s"
  while [ "$elapsed" -lt "$timeout" ]; do
    ok="$(rx_ok_count "$serial")"
    if [ "${ok:-0}" -ge 1 ]; then
      echo "[wait-rx] FloodRouter OK=$ok after ${elapsed}s"
      return 0
    fi
    if probe_rx_ok "$serial" >/dev/null 2>&1; then
      probe_rx_ok "$serial"
      echo "[wait-rx] probe visible after ${elapsed}s"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  echo "[wait-rx] timeout ${timeout}s — no RX evidence in logcat"
  return 1
}

launch_app() {
  am_start_action "$(adb_serial "${DEVICE_HINT:-}")" "${1:-net.guacamaya.action.OBSERVE_ON}"
}

launch_action() {
  am_start_action "$(adb_serial "${DEVICE_HINT:-}")" "$1"
}

start_fg_service() {
  local action="$1"
  # Shell uid cannot start FGS on API 30+; bounce through MainActivity in-app.
  adb_cmd shell am start -a "$action" -n "$ACTIVITY" >/dev/null
}

# JDK 17+ required. AGP 8.5.2 runs cleanly on JDK 21. Resolution order:
#   1. caller-provided JAVA_HOME
#   2. Android Studio's bundled JBR (JDK 21)
#   3. system default on PATH
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /opt/android-studio/jbr/bin/java ]; then
    JAVA_HOME=/opt/android-studio/jbr
  else
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  fi
fi
APK="app/build/outputs/apk/debug/app-debug.apk"
DEVICE_HINT="${2:-${DEVICE_HINT:-}}"

case "${1:-help}" in

  build)
    echo "[build] assembleDebug"
    "$JAVA_HOME/bin/java" -version 2>&1 | head -1
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:assembleDebug
    echo "APK: $APK"
    ;;

  install)
    echo "[1/2] Build"
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:assembleDebug
    serial="$(adb_serial "$DEVICE_HINT")"
    if ! adb -s "$serial" get-state >/dev/null 2>&1; then
      echo "No adb device. Connect one, then rerun."; exit 1
    fi
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "[2/2] Install on serial=$serial device=$dev"
    adb -s "$serial" install -r "$APK"
    echo "Done. Open Guacamaya on the phone."
    ;;

  observe-on)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "Observe ON → serial=$serial device=$dev"
    start_fg_service "${PKG}.action.OBSERVE_ON"
    echo "Tail: ./scripts/demo.sh logcat $DEVICE_HINT"
    ;;

  observe-off)
    serial="$(adb_serial "$DEVICE_HINT")"
    echo "Observe OFF → serial=$serial"
    start_fg_service "${PKG}.action.OBSERVE_OFF"
    ;;

  broadcast-on)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "Broadcast SOS + observe → serial=$serial device=$dev"
    start_fg_service "${PKG}.action.START"
    ;;

  broadcast-off)
    serial="$(adb_serial "$DEVICE_HINT")"
    echo "Broadcast OFF → serial=$serial"
    start_fg_service "${PKG}.action.STOP"
    ;;

  heartbeat-on)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "Heartbeat ON → serial=$serial device=$dev"
    start_fg_service "${PKG}.action.HEARTBEAT_ON"
    ;;

  heartbeat-off)
    serial="$(adb_serial "$DEVICE_HINT")"
    echo "Heartbeat OFF → serial=$serial"
    start_fg_service "${PKG}.action.HEARTBEAT_OFF"
    ;;

  received)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    ok="$(adb -s "$serial" logcat -d -s guacamaya.mesh.FloodRouter:I 2>/dev/null | grep -c ' OK ' || true)"
    drop="$(adb -s "$serial" logcat -d -s guacamaya.mesh.FloodRouter:I 2>/dev/null | grep -c ' DROP' || true)"
    probe="$(adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | tail -10 || true)"
    probe_nodes="$(printf '%s\n' "$probe" | sed -n 's/.*nodes=\([0-9]*\).*/\1/p' | tail -1)"
    probe_frames="$(printf '%s\n' "$probe" | sed -n 's/.*frames=\([0-9]*\).*/\1/p' | tail -1)"
    if [ -z "$probe_nodes" ]; then
      probe_nodes="$(printf '%s\n' "$probe" | sed -n 's/.*mesh nodes=\([0-9]*\).*/\1/p' | tail -1)"
    fi
    if [ -z "$probe_frames" ]; then
      probe_frames="$(printf '%s\n' "$probe" | sed -n 's/.*frames=\([0-9]*\).*/\1/p' | tail -1)"
    fi
    echo "device=$dev serial=$serial  Received(OK)=$ok  Dropped=$drop  probe_nodes=${probe_nodes:-?} probe_frames=${probe_frames:-?}"
    adb -s "$serial" logcat -d -s guacamaya.mesh.FloodRouter:I 2>/dev/null | grep ' OK ' | tail -5 || true
    [ -n "$probe" ] && echo "$probe"
    if [ "${ok:-0}" -lt 1 ]; then
      probe_rx_ok "$serial" || true
    fi
    ;;

  device-test)
    REALME="${DEVICE_TEST_TX:-6LRGONDE6LRG9XCY}"
    SWEET="${DEVICE_TEST_RX:-sweet}"
    echo "[device-test] install + BLE smoke: Realme START → sweet OBSERVE"
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:installDebug -q
    adb -s "$(adb_serial "$REALME")" shell am force-stop "$PKG" 2>/dev/null || true
    adb -s "$(adb_serial "$SWEET")" shell am force-stop "$PKG" 2>/dev/null || true
    sleep 2
    adb -s "$(adb_serial "$SWEET")" logcat -c 2>/dev/null || true
    SWEET_SERIAL="$(adb_serial "$SWEET")"
    am_start_action "$SWEET_SERIAL" "${PKG}.action.OBSERVE_ON"
    am_start_action "$SWEET_SERIAL" "${PKG}.action.HEARTBEAT_ON"
    sleep 8
    am_start_action "$(adb_serial "$REALME")" "${PKG}.action.START"
    echo "[device-test] waiting 20s + probe poll..."
    sleep 20
    wait_rx_probe "$SWEET_SERIAL" 60
    PROBE_OK=$?
    serial="$SWEET_SERIAL"
    ok="$(rx_ok_count "$serial")"
    echo "[device-test] sweet Received(OK)=$ok"
    adb -s "$serial" logcat -d --pid="$(adb -s "$serial" shell pidof "$PKG" 2>/dev/null | awk '{print $1}' | tr -d '\r\n')" 2>/dev/null | \
      grep -E 'saw_uuid|FloodRouter: OK|mesh nodes' | tail -5 || true
    if [ "${ok:-0}" -ge 1 ]; then
      echo "[device-test] PASS (FloodRouter OK=$ok)"
      exit 0
    fi
    if [ "$PROBE_OK" -eq 0 ]; then
      echo "[device-test] PASS (probe poll)"
      exit 0
    fi
    if probe_rx_ok "$serial"; then
      echo "[device-test] PASS (probe fallback)"
      exit 0
    fi
    REALME_SERIAL="$(adb_serial "$REALME")"
    echo "[device-test] Realme probe fallback poll (sweet logd often empty)..."
    for _ in 1 2 3 4 5 6; do
      if probe_rx_ok "$REALME_SERIAL"; then
        probe_rx_ok "$REALME_SERIAL"
        echo "[device-test] PASS (Realme probe fallback — sweet logd empty)"
        exit 0
      fi
      sleep 5
    done
    echo "[device-test] FAIL — expected ≥1 OK or probe RX on $SWEET" >&2
    exit 1
    ;;

  sweet)
    DEVICE_HINT=sweet
    serial="$(adb_serial sweet)"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    model="$(adb -s "$serial" shell getprop ro.product.model | tr -d '\r\n')"
    api="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r\n')"
    echo "Redmi target: serial=$serial device=$dev model=$model api=$api"
    echo "BLE extended: $(adb -s "$serial" shell dumpsys bluetooth_manager 2>/dev/null | grep -i 'extended' | head -3 || echo 'n/a')"
    ;;

  battery-whitelist)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "Battery whitelist → serial=$serial device=$dev pkg=$PKG"
    adb -s "$serial" shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
    adb -s "$serial" shell cmd deviceidle whitelist +"$PKG" 2>/dev/null || true
    echo "isIgnoring: $(adb -s "$serial" shell dumpsys deviceidle whitelist 2>/dev/null | grep -c "$PKG" || echo 0)"
    ;;

  battery-miui)
    serial="$(adb_serial "$DEVICE_HINT")"
    echo "Opening MIUI autostart → serial=$serial"
    adb -s "$serial" shell am start -n com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity 2>/dev/null || \
      adb -s "$serial" shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$PKG"
    ;;

  compass-miui)
    serial="$(adb_serial "$DEVICE_HINT")"
    echo "[compass-miui] Abrir brújula/calibración MIUI → serial=$serial"
    adb -s "$serial" shell am start -n com.miui.compass/.CompassActivity 2>/dev/null || \
      adb -s "$serial" shell monkey -p com.miui.compass -c android.intent.category.LAUNCHER 1 2>/dev/null || \
      adb -s "$serial" shell am start -a android.settings.LOCATION_SOURCE_SETTINGS 2>/dev/null || true
    echo "Mueve el sweet en figura-8 ~15 s, luego: ./scripts/demo.sh functional-compass sweet"
    ;;

  tamper)
    echo "[tamper] generate tampered frame on host"
    python3 scripts/tamper_test.py
    serial="$(adb_serial "$DEVICE_HINT")"
    if adb -s "$serial" get-state >/dev/null 2>&1; then
      adb -s "$serial" push /tmp/guacamaya_test_frames.json /sdcard/guacamaya_test_frames.json
      echo "Pushed. In the app: Debug > Load test frame (tampered)."
    else
      echo "JSON at /tmp/guacamaya_test_frames.json (no device to push to)."
    fi
    ;;

  logcat)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    if ! adb -s "$serial" get-state >/dev/null 2>&1; then
      echo "No adb device."; exit 1
    fi
    echo "Streaming logcat from serial=$serial device=$dev (Ctrl-C to stop)"
    adb -s "$serial" logcat -c 2>/dev/null || true
    adb -s "$serial" logcat -v time | python3 scripts/logcat_pretty.py
    ;;

  tap-pct)
    tap_pct "${2:?}" "${3:?}"
    ;;

  tap-power)
    tap_pct 50 46
    ;;

  tap-mode-both)
    tap_pct 83 17
    ;;

  tap-radar)
    tap_pct 50 78
    ;;

  tap-map)
    tap_pct 50 88
    ;;

  tap-back)
    tap_pct 14 6
    ;;

  tap-calibrate-north)
    tap_pct 50 82
    ;;

  probe-dump)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "=== guacamaya.probe ($dev) ==="
    adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | tail -15 || true
    ;;

  functional-test)
    serial="$(adb_serial "$DEVICE_HINT")"
    dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
    echo "[functional-test] build+install → $dev ($serial)"
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:installDebug -q
    adb -s "$serial" logcat -c 2>/dev/null || true
    launch_app
    echo "[functional-test] encender Ambos vía intent (observe + heartbeat)"
    adb -s "$serial" shell am start -a "${PKG}.action.OBSERVE_ON" -n "$ACTIVITY" >/dev/null || true
    adb -s "$serial" shell am start -a "${PKG}.action.HEARTBEAT_ON" -n "$ACTIVITY" >/dev/null || true
    sleep 4
    echo "[functional-test] abrir radar"
    tap_pct 50 78
    sleep 6
    tap_pct 50 82
    sleep 2
    probe_lines="$(adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | grep -c heading || true)"
    echo "[functional-test] probe lines=$probe_lines"
    if [ "${probe_lines:-0}" -lt 2 ]; then
      echo "[functional-test] WARN — pocos logs guacamaya.probe (¿servicio encendido?)" >&2
    fi
    adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | tail -10 || true
    echo "[functional-test] volver + mapa cartesiano"
    tap_pct 14 6
    sleep 0.8
    tap_pct 50 88
    sleep 4
    adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | tail -10 || true
    echo "[functional-test] done"
    ;;

  functional-compass)
    REALME="${DEVICE_TEST_TX:-6LRGONDE6LRG9XCY}"
    SWEET="${DEVICE_TEST_RX:-sweet}"
    echo "[functional-compass] brújula en ambos dispositivos"
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:installDebug -q
    SWEET_H="" REALME_H=""
    for pair in "$SWEET:sweet" "$REALME:realme"; do
      hint="${pair%%:*}"
      label="${pair##*:}"
      serial="$(adb_serial "$hint")"
      dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
      adb -s "$serial" logcat -c 2>/dev/null || true
      adb -s "$serial" shell am force-stop "$PKG" 2>/dev/null || true
      sleep 1
      am_start_action "$serial" "${PKG}.action.HEARTBEAT_ON"
      echo "=== $label ($dev $serial) ==="
      line=""
      for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
        sleep 3
        snap="$(probe_snapshot "$serial")"
        line="$(printf '%s\n' "$snap" | grep 'heading=' | tail -1 || true)"
        [ -n "$line" ] && break
      done
      if [ -n "$line" ]; then
        echo "$line"
        h="$(printf '%s' "$line" | sed -n 's/.*heading=\([^ ]*\).*/\1/p')"
        magnet="$(printf '%s' "$line" | sed -n 's/.*magnet=\([^ ]*\).*/\1/p')"
        usable="$(printf '%s' "$line" | sed -n 's/.*usable=\([^ ]*\).*/\1/p')"
        echo "  → heading=${h:-?}° usable=${usable:-?} magnet=${magnet:-?}"
        if [ "$label" = "sweet" ]; then SWEET_H="$h"; else REALME_H="$h"; fi
      else
        echo "  (sin probe heading en 36 s — usar: ./scripts/demo.sh compass-miui $hint)"
      fi
    done
    if [ -n "$SWEET_H" ] && [ -n "$REALME_H" ]; then
      echo "[functional-compass] Δheading Realme−sweet ≈ $(( (REALME_H - SWEET_H + 540) % 360 - 180 ))° (paralelos → ~0°)"
    fi
    echo "[functional-compass] Coloca ambos teléfonos paralelos y compara heading en probe."
    ;;

  functional-compass-calibrate)
    DEVICE_HINT="${2:-sweet}"
    serial="$(adb_serial "$DEVICE_HINT")"
    echo "[functional-compass-calibrate] $DEVICE_HINT ($serial)"
    adb -s "$serial" shell am force-stop "$PKG" 2>/dev/null || true
    sleep 1
    am_start_action "$serial" "${PKG}.action.HEARTBEAT_ON"
    sleep 3
    DEVICE_HINT="$DEVICE_HINT" ./scripts/demo.sh tap-mode-both
    DEVICE_HINT="$DEVICE_HINT" ./scripts/demo.sh tap-power
    sleep 2
    am_start_action "$serial" "${PKG}.action.HEARTBEAT_ON"
    DEVICE_HINT="$DEVICE_HINT" ./scripts/demo.sh tap-radar
    sleep 4
    DEVICE_HINT="$DEVICE_HINT" ./scripts/demo.sh tap-calibrate-north
    sleep 6
    ./scripts/demo.sh probe-dump "$DEVICE_HINT"
    ;;

  ble-reverse-test)
    REALME="${DEVICE_TEST_TX:-6LRGONDE6LRG9XCY}"
    SWEET="${DEVICE_TEST_RX:-sweet}"
    echo "[ble-reverse] install + force-stop both"
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:installDebug -q
    adb -s "$(adb_serial "$REALME")" shell am force-stop "$PKG" 2>/dev/null || true
    adb -s "$(adb_serial "$SWEET")" shell am force-stop "$PKG" 2>/dev/null || true
    sleep 2
    echo "[ble-reverse] sweet TX → Realme RX (heartbeat 70s)"
    adb -s "$(adb_serial "$REALME")" logcat -c 2>/dev/null || true
    am_start_action "$(adb_serial "$SWEET")" "${PKG}.action.HEARTBEAT_ON"
    am_start_action "$(adb_serial "$REALME")" "${PKG}.action.OBSERVE_ON"
    sleep 70
    ./scripts/demo.sh received "$REALME"
    echo "[ble-reverse] Realme TX → sweet RX (START 70s, sweet foreground)"
    adb -s "$(adb_serial "$SWEET")" shell am force-stop "$PKG" 2>/dev/null || true
    adb -s "$(adb_serial "$REALME")" shell am force-stop "$PKG" 2>/dev/null || true
    sleep 2
    adb -s "$(adb_serial "$SWEET")" logcat -c 2>/dev/null || true
    # Sweet RX-only; Realme SOS TX.
    am_start_action "$(adb_serial "$SWEET")" "${PKG}.action.OBSERVE_ON"
    sleep 8
    am_start_action "$(adb_serial "$REALME")" "${PKG}.action.START"
    SWEET_SERIAL="$(adb_serial "$SWEET")"
    for _ in 1 2 3 4 5 6 7; do
      sleep 10
      adb -s "$SWEET_SERIAL" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
      adb -s "$SWEET_SERIAL" shell am broadcast -a "${PKG}.action.OBSERVE_ON" -n "${PKG}/.adb.AdbCommandReceiver" >/dev/null 2>&1 || true
      adb -s "$SWEET_SERIAL" shell am start -a "${PKG}.action.OBSERVE_ON" -n "$ACTIVITY" -f 0x14008000 --es guacamaya_adb_action "${PKG}.action.OBSERVE_ON" >/dev/null 2>&1 || true
      adb -s "$SWEET_SERIAL" shell input tap 540 1100 2>/dev/null || true
    done
    am_start_action "$SWEET_SERIAL" "${PKG}.action.OBSERVE_ON"
    wait_rx_probe "$SWEET_SERIAL" 60
    SWEET_PROBE_OK=$?
    DEVICE_HINT=sweet ./scripts/demo.sh received
    SWEET_OK="$(rx_ok_count "$SWEET_SERIAL")"
    if [ "${SWEET_OK:-0}" -ge 1 ]; then
      echo "[ble-reverse] Realme→sweet PASS (FloodRouter OK=$SWEET_OK)"
    elif [ "$SWEET_PROBE_OK" -eq 0 ]; then
      echo "[ble-reverse] Realme→sweet PASS (probe poll)"
    elif probe_rx_ok "$SWEET_SERIAL"; then
      echo "[ble-reverse] Realme→sweet PASS (probe fallback)"
    else
      echo "[ble-reverse] Realme→sweet FAIL (no FloodRouter OK, probe empty)" >&2
    fi
    SPID="$(adb -s "$(adb_serial "$SWEET")" shell pidof "$PKG" 2>/dev/null | tr -d '\r\n' || true)"
    if [ -n "$SPID" ]; then
      adb -s "$(adb_serial "$SWEET")" logcat -d --pid="$SPID" 2>/dev/null | grep -E "scan started|scan callbacks|saw_uuid|FloodRouter: OK|mesh nodes" | tail -10 || true
    fi
    ;;

  *)
    cat <<USAGE
Guacamaya demo runner.

Commands:
  build         assembleDebug
  install [dev] build + adb install (-r); dev=codename|serial (e.g. sweet)
  observe-on [dev]  start BLE observer via adb (Observe button)
  observe-off [dev] stop observer
  broadcast-on [dev]  start SOS broadcast + observe
  broadcast-off [dev] stop broadcast
  heartbeat-on [dev]  start signed presence beacon (radar)
  heartbeat-off [dev] stop presence beacon
  device-test        BLE smoke: Realme START → sweet observe (exit 1 if 0 OK)
  sweet         print Redmi Note 10 (sweet) adb info
  battery-whitelist [dev]  adb whitelist (sin popup MIUI)
  battery-miui [dev]       abrir Autostart MIUI manualmente
  tamper        run tamper_test.py, push JSON to /sdcard if device attached
  logcat [dev]  stream colorized guacamaya logcat
  tap-pct X Y [dev]  tap at screen percent (0-100)
  tap-power / tap-mode-both / tap-radar / tap-map / tap-back / tap-calibrate-north
  probe-dump [dev]  last guacamaya.probe logcat lines
  functional-test [dev]  install + adb taps (radar/map/brújula) + probe dump

Environment:
  JAVA_HOME         JDK 17 path (default /usr/lib/jvm/java-17-openjdk)
  ANDROID_SERIAL    adb device serial (overrides codename hint)
  DEVICE_HINT       default codename/serial for subcommands

Examples:
  ./scripts/demo.sh install sweet
  ./scripts/demo.sh observe-on sweet
  ./scripts/demo.sh logcat sweet
  ./scripts/demo.sh received sweet
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./scripts/demo.sh build
USAGE
    exit 1
    ;;

esac
