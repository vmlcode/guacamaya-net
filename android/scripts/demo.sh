#!/usr/bin/env bash
# SOSNet — demo runner.
#
# Usage:
#   ./scripts/demo.sh build     # assemble debug APK
#   ./scripts/demo.sh install   # build + install on first connected device
#   ./scripts/demo.sh tamper    # generate tampered frame JSON on host
#   ./scripts/demo.sh logcat    # stream colorized logcat from first device
#
# Multiple devices: set ANDROID_SERIAL to pick one (standard adb behavior).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

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
    if ! adb get-state >/dev/null 2>&1; then
      echo "No adb device. Connect one, then rerun."; exit 1
    fi
    echo "[2/2] Install on $(adb get-state 2>/dev/null)"
    adb install -r "$APK"
    echo "Done. Open SOSNet on the phone."
    ;;

  tamper)
    echo "[tamper] generate tampered frame on host"
    python3 scripts/tamper_test.py
    if adb get-state >/dev/null 2>&1; then
      adb push /tmp/sosnet_test_frames.json /sdcard/sosnet_test_frames.json
      echo "Pushed. In the app: Debug > Load test frame (tampered)."
    else
      echo "JSON at /tmp/sosnet_test_frames.json (no device to push to)."
    fi
    ;;

  logcat)
    if ! adb get-state >/dev/null 2>&1; then
      echo "No adb device."; exit 1
    fi
    echo "Streaming colorized logcat (Ctrl-C to stop)"
    adb logcat -c 2>/dev/null || true
    adb logcat -v time | python3 scripts/logcat_pretty.py
    ;;

  *)
    cat <<USAGE
SOSNet demo runner.

Commands:
  build    assembleDebug (produces app/build/outputs/apk/debug/app-debug.apk)
  install  build + adb install -r on the connected device
  tamper   run tamper_test.py, push JSON to /sdcard if a device is attached
  logcat   stream colorized logcat from the connected device

Environment:
  JAVA_HOME     JDK 17 path (default /usr/lib/jvm/java-17-openjdk)
  ANDROID_SERIAL  standard adb device selector when several are plugged in

Example:
  ./scripts/demo.sh install
USAGE
    exit 1
    ;;

esac
