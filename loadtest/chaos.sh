#!/usr/bin/env bash
# Change backend behaviour while a wave runs.  Usage: loadtest/chaos.sh <preset> [port]
#   calm     normal latency, no errors
#   slow     15% of requests +3 s (a slow dependency)
#   errors   8% 500s
#   drift    a "backend release" changes the sync schema: breaks older clients (crash fault #2)
port=${2:-8080}
case "$1" in
  calm)   body='{"latency_ms":60,"jitter_ms":40,"error_pct":0,"slow_pct":0,"drift_pct":0}' ;;
  slow)   body='{"slow_pct":15,"slow_ms":3000}' ;;
  errors) body='{"error_pct":8}' ;;
  drift)  body='{"drift_pct":30}' ;;
  *) echo "usage: $0 calm|slow|errors|drift [port]"; exit 1 ;;
esac
curl -s -XPOST "localhost:$port/admin/chaos" -d "$body"; echo
