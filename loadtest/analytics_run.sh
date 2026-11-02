#!/usr/bin/env bash
# Fill the otel and edot analytics dashboards: alternating rounds of autopilot sessions on one Android emulator,
# so both SDKs cover the same time window and the same backend conditions (calm -> errors -> slow -> ...).
#   loadtest/analytics_run.sh [rounds=6] [minutes_per_flavor_per_round=5]
# Needs: backend running (python3 backend/server.py), an emulator up, otelDebug and edotFullDebug installed.
set -euo pipefail
cd "$(dirname "$0")/.."
ROUNDS=${1:-6}; MIN=${2:-5}
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
"$ADB" shell settings put global hide_error_dialogs 1
presets=(calm errors calm slow calm errors)
for r in $(seq 1 "$ROUNDS"); do
  preset=${presets[$(( (r - 1) % ${#presets[@]} ))]}
  loadtest/chaos.sh "$preset" >/dev/null; echo "[$(date +%T)] round $r/$ROUNDS backend: $preset"
  for mode in otel edotFull; do
    python3 loadtest/wave.py --mode "$mode" --platforms android --minutes "$MIN" --session-seconds 12 --speed 3 \
      --vusers 2 --faults mild --new-install-prob 0.5 --crash-prob 0.12 --wave "r$r" | tail -n 25
  done
done
loadtest/chaos.sh calm >/dev/null
echo "[$(date +%T)] done"
