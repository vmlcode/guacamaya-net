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
  # MIUI sweet: `am start -W` can hang indefinitely — cap wait, then retry without -W.
  if timeout 12 adb -s "$serial" shell am start -W -a "$action" -n "$ACTIVITY" -f "$flags" >/dev/null 2>&1; then
    :
  else
    adb -s "$serial" shell am start -a "$action" -n "$ACTIVITY" -f "$flags" >/dev/null 2>&1 || true
  fi
  sleep 1
  # Second dispatch triggers MainActivity.onResume → FGS foreground on MIUI.
  adb -s "$serial" shell am start -a "$action" -n "$ACTIVITY" -f "$flags" >/dev/null 2>&1 || true
  sleep 1
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
    echo "device=$dev serial=$serial  Received(OK)=$ok  Dropped=$drop"
    adb -s "$serial" logcat -d -s guacamaya.mesh.FloodRouter:I 2>/dev/null | grep ' OK ' | tail -5 || true
    ;;

  device-test)
    REALME="${DEVICE_TEST_TX:-6LRGONDE6LRG9XCY}"
    SWEET="${DEVICE_TEST_RX:-sweet}"
    echo "[device-test] install + BLE smoke: TX=$REALME → observe $SWEET"
    JAVA_HOME="$JAVA_HOME" ./gradlew :app:installDebug -q
    adb -s "$(adb_serial "$REALME")" shell am start -n "$ACTIVITY" >/dev/null || true
    adb -s "$(adb_serial "$SWEET")" shell am start -n "$ACTIVITY" >/dev/null || true
    adb -s "$(adb_serial "$SWEET")" logcat -c 2>/dev/null || true
    start_fg_service() {
      local serial action
      serial="$1"; action="$2"
      adb -s "$serial" shell am start -a "$action" -n "$ACTIVITY" >/dev/null
    }
    start_fg_service "$(adb_serial "$SWEET")" "${PKG}.action.OBSERVE_ON"
    start_fg_service "$(adb_serial "$REALME")" "${PKG}.action.START"
    echo "[device-test] waiting 20s..."
    sleep 20
    serial="$(adb_serial "$SWEET")"
    ok="$(adb -s "$serial" logcat -d -s guacamaya.mesh.FloodRouter:I 2>/dev/null | grep -c ' OK ' || true)"
    echo "[device-test] sweet Received(OK)=$ok"
    adb -s "$serial" logcat -d -s guacamaya.ble.Observer:D guacamaya.mesh.FloodRouter:I 2>/dev/null | tail -8 || true
    if [ "${ok:-0}" -lt 1 ]; then
      echo "[device-test] FAIL — expected ≥1 OK on $SWEET" >&2
      exit 1
    fi
    echo "[device-test] PASS"
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
    for pair in "$SWEET:sweet" "$REALME:realme"; do
      hint="${pair%%:*}"
      label="${pair##*:}"
      serial="$(adb_serial "$hint")"
      dev="$(adb -s "$serial" shell getprop ro.product.device | tr -d '\r\n')"
      adb -s "$serial" logcat -c 2>/dev/null || true
      adb -s "$serial" shell am force-stop "$PKG" 2>/dev/null || true
      sleep 1
      am_start_action "$serial" "${PKG}.action.HEARTBEAT_ON"
      sleep 12
      echo "=== $label ($dev $serial) ==="
      adb -s "$serial" logcat -d -s guacamaya.probe:I 2>/dev/null | tail -4 || true
    done
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
    sleep 2
    adb -s "$(adb_serial "$SWEET")" logcat -c 2>/dev/null || true
    am_start_action "$(adb_serial "$REALME")" "${PKG}.action.START"
    am_start_action "$(adb_serial "$SWEET")" "${PKG}.action.OBSERVE_ON"
    SWEET_SERIAL="$(adb_serial "$SWEET")"
    for _ in 1 2 3 4 5 6 7; do
      sleep 10
      adb -s "$SWEET_SERIAL" shell input keyevent KEYCODE_WAKEUP 2>/dev/null || true
      adb -s "$SWEET_SERIAL" shell am start -a "${PKG}.action.OBSERVE_ON" -n "$ACTIVITY" >/dev/null 2>&1 || true
      adb -s "$SWEET_SERIAL" shell input tap 540 1100 2>/dev/null || true
    done
    sleep 5
    ./scripts/demo.sh received "$SWEET"
    SPID="$(adb -s "$(adb_serial "$SWEET")" shell pidof "$PKG" 2>/dev/null | tr -d '\r\n' || true)"
    if [ -n "$SPID" ]; then
      adb -s "$(adb_serial "$SWEET")" logcat -d --pid="$SPID" 2>/dev/null | grep -E "scan started|FloodRouter: OK" | tail -5 || true
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
