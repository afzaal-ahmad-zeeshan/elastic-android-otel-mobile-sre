#!/usr/bin/env bash
# Start/stop a fleet of headless Android emulators with all five app flavors installed.
#   loadtest/devices.sh up <android_count>
#   loadtest/devices.sh status
#   loadtest/devices.sh down
# Then: python3 loadtest/wave.py --mode edot --minutes 10      (uses every running device)
#
# Android: clones of one AVD (tele-37) run with -read-only so many can share it, on ports 5554, 5556, ...
# Rough cost: ~2 GB RAM per emulator. Build the apps first (README).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
AVD="${AVD:-tele-37}"
LOGS="${TMPDIR:-/tmp}/tele-fleet"; mkdir -p "$LOGS"

up_android() {
  local n=$1; [ "$n" -gt 0 ] || return 0
  "$ADB" start-server >/dev/null
  for i in $(seq 0 $((n - 1))); do
    local port=$((5554 + 2 * i))
    if "$ADB" devices | grep -q "emulator-$port"; then continue; fi
    nohup "$SDK/emulator/emulator" -avd "$AVD" -read-only -port "$port" -no-audio -no-boot-anim \
      -no-window -gpu host -memory 2048 > "$LOGS/emu-$port.log" 2>&1 &
    sleep 4   # stagger: simultaneous cold boots fight over CPU
  done
  for i in $(seq 0 $((n - 1))); do
    local s="emulator-$((5554 + 2 * i))"
    "$ADB" -s "$s" wait-for-device
    until [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 3; done
    "$ADB" -s "$s" shell settings put global hide_error_dialogs 1
    for f in bare otel edot edotFull edotFullTraced; do
      "$ADB" -s "$s" install -r "$ROOT/android/app/build/outputs/apk/$f/debug/app-$f-debug.apk" >/dev/null
    done
    echo "android $s ready"
  done
}

case "${1:-}" in
  up) up_android "${2:-0}" ;;
  status)
    "$ADB" devices | grep emulator || echo "  none" ;;
  down)
    for s in $("$ADB" devices | awk '/^emulator/ {print $1}'); do "$ADB" -s "$s" emu kill >/dev/null || true; done ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
