#!/usr/bin/env bash
# Create the initial HyperDX team and publish its ingestion key.
#
# This is not a convenience: until a team exists, the app pushes the collector
# no OpAMP configuration, so the collector runs with *no OTLP receivers at all*
# — 4317/4318 are unbound and every exporter gets a connection reset. A
# ClickStack that nobody has signed into is not a running deployment, so
# convergence creates the team rather than waiting for a human.
#
# The ingestion key is the team's `apiKey`, minted by the app. It cannot be
# chosen in advance, which is why this runs after the stack is up and rewrites
# ingestion.env in place.
#
# Idempotent: `/installation` reports whether a team already exists, and the
# key is only rewritten when it actually changed.
set -euo pipefail

. /etc/clickstack/admin.env

compose="docker compose -f /opt/clickstack/compose.yml"
api="http://127.0.0.1:8000"

for _ in $(seq 1 60); do
  curl -fsS "$api/installation" >/dev/null 2>&1 && break
  sleep 5
done

existing=$(curl -fsS "$api/installation" | grep -o '"isTeamExisting":[a-z]*' | cut -d: -f2)
if [ "$existing" != "true" ]; then
  # Credentials come from admin.env and are never echoed.
  curl -fsS -X POST -H 'content-type: application/json' \
    --data "$(printf '{"email":"%s","password":"%s","confirmPassword":"%s"}' \
                "$HYPERDX_ADMIN_EMAIL" "$HYPERDX_ADMIN_PASSWORD" "$HYPERDX_ADMIN_PASSWORD")" \
    "$api/register/password" >/dev/null
fi

key=$($compose exec -T db mongosh hyperdx --quiet --eval 'db.teams.findOne().apiKey' | tr -d '[:space:]')
case "$key" in
  '' | *[!0-9a-fA-F-]*)
    echo "clickstack-setup: no usable team API key was minted" >&2
    exit 1
    ;;
esac

line="HYPERDX_API_KEY=$key"
if [ -f /etc/clickstack/ingestion.env ] && [ "$(cat /etc/clickstack/ingestion.env)" = "$line" ]; then
  echo unchanged
  exit 0
fi

umask 077
printf '%s\n' "$line" > /etc/clickstack/ingestion.env
echo changed
