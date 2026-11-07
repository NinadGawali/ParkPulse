#!/usr/bin/env bash
# Creates the repo-root .env from .env.example, filling every empty value with a random
# local-only secret. Never prints the values. Does nothing if .env already exists.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
example="$root/.env.example"
target="$root/.env"

if [[ -f "$target" ]]; then
  echo "init-local-env: .env already exists, leaving it unchanged."
  exit 0
fi

rand() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }

umask 077
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  if [[ "$line" =~ ^([A-Z0-9_]+)=$ ]]; then
    printf '%s=%s\n' "${BASH_REMATCH[1]}" "$(rand)"
  else
    printf '%s\n' "$line"
  fi
done < "$example" > "$target"

echo "init-local-env: created .env with generated local secrets (values not shown)."
