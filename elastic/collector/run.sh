#!/bin/sh
# Start the project's EDOT gateway on host ports 14317 (gRPC) / 14318 (HTTP), forwarding to the local stack on :9200.
# Android emulator reaches it at http://10.0.2.2:14318. Password comes from ../../elastic-start-local/.env (not stored here).
set -e
D=$(cd "$(dirname "$0")" && pwd)
. "$D/../../../elastic-start-local/.env"
docker rm -f mobile-edot >/dev/null 2>&1 || true
docker run -d --name mobile-edot \
  -p 127.0.0.1:14317:4317 -p 127.0.0.1:14318:4318 \
  -v "$D/gateway.yml:/etc/edot/gateway.yml:ro" \
  -e ELASTIC_ENDPOINT=http://host.docker.internal:9200 -e ELASTIC_USER=elastic -e ELASTIC_PASSWORD="$ES_LOCAL_PASSWORD" \
  docker.elastic.co/elastic-agent/elastic-otel-collector:9.5.4 --config=/etc/edot/gateway.yml
