#!/usr/bin/env bash
# Starts local infrastructure (Postgres, Kafka, Redis, S3) and waits until healthy.
# Usage: infra-up.sh [--profile search]
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
envfile="$root/.env"
[[ -f "$envfile" ]] || bash "$root/infra/scripts/init-local-env.sh"
compose=(docker compose --env-file "$envfile" -f "$root/infra/docker-compose.yml" "$@")
"${compose[@]}" up -d --wait
"${compose[@]}" ps --format '{{.Name}} {{.Status}}'
