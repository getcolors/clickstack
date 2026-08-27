#!/usr/bin/env bash
# End-to-end ingest proof, run on the server where the generated ingestion key
# lives. Sends one OTLP/HTTP log record through the collector and waits for it
# to land in ClickHouse, so a pass means receiver, exporter, and database all
# work — not merely that a port answers.
set -euo pipefail

. /etc/clickstack/ingestion.env

service="clickstack-smoke-$(date +%s)"
now_ns="$(date +%s)000000000"

payload=$(cat <<JSON
{"resourceLogs":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"$service"}}]},
"scopeLogs":[{"logRecords":[{"timeUnixNano":"$now_ns","severityText":"INFO",
"body":{"stringValue":"clickstack create-time acceptance smoke"}}]}]}]}
JSON
)

curl -fsS -X POST http://127.0.0.1:4318/v1/logs \
  -H 'content-type: application/json' \
  -H "authorization: $HYPERDX_API_KEY" \
  --data "$payload" >/dev/null

# The exporter batches, so the row is not instantaneous.
for _ in $(seq 1 30); do
  count=$(docker compose -f /opt/clickstack/compose.yml exec -T ch-server \
            clickhouse-client --query \
            "SELECT count() FROM default.otel_logs WHERE ServiceName = '$service'" \
            2>/dev/null | tr -d '[:space:]' || true)
  if [ -n "${count:-}" ] && [ "$count" -gt 0 ] 2>/dev/null; then
    echo "clickstack smoke: $count row(s) for $service"
    exit 0
  fi
  sleep 5
done

echo "clickstack smoke: no row reached default.otel_logs for $service" >&2
exit 1
