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
