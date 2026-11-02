#!/usr/bin/env bash
# Hands-off scenario: autopilot users on every running device while the backend goes through a timeline of
# calm -> errors -> slow dependency -> recovery. Nothing to tap.
#   loadtest/scenario.sh <bare|otel|edot|edotFull|edotFullTraced> [minutes=10] [platforms=android] [faults=mild]
# Needs: backend running (python3 backend/server.py), emulators up (loadtest/devices.sh status).
set -euo pipefail
cd "$(dirname "$0")/.."
MODE=${1:?mode: bare|otel|edot|edotFull|edotFullTraced}; MIN=${2:-10}; PLAT=${3:-android}; FAULTS=${4:-mild}
PORT=${BACKEND_PORT:-8080}
S=$(python3 -c "print(int($MIN*60))")
phase() { sleep "$(python3 -c "print(int($S*$1))")"; loadtest/chaos.sh calm "$PORT" >/dev/null; loadtest/chaos.sh "$2" "$PORT" >/dev/null; echo "[$(date +%T)] backend: $2"; }

loadtest/chaos.sh calm "$PORT" >/dev/null; echo "[$(date +%T)] backend: calm"
python3 loadtest/wave.py --mode "$MODE" --minutes "$MIN" --platforms "$PLAT" --faults "$FAULTS" \
  --vusers "${VUSERS:-10}" --session-seconds "${SESSION_SECONDS:-45}" --wave "${MODE:0:1}$(date +%H%M)" &
WAVE=$!
phase 0.25 errors   # 8% of requests fail
phase 0.30 slow     # 15% of requests +3 s
phase 0.25 calm
wait "$WAVE"
loadtest/chaos.sh calm "$PORT" >/dev/null
