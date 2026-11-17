#!/usr/bin/env bash
# Creates or tops up the repo-root .env from .env.example. Every key that is empty in the example
# gets a random local-only secret. Existing values are never changed; keys added to the example
# later are appended. Never prints any value.
set -euo pipefail

#
# Usage: init-local-env.sh               create/top up
#        init-local-env.sh set KEY VALUE  change one non-secret key (e.g. a port)

root="$(cd "$(dirname "$0")/../.." && pwd)"
example="$root/.env.example"
target="$root/.env"

if [[ "${1:-}" == "set" ]]; then
  key="${2:?key required}"
  value="${3:?value required}"
  [[ "$key" =~ ^[A-Z0-9_]+_PORT$ ]] || { echo "init-local-env: only *_PORT keys can be set this way"; exit 1; }
  [[ -f "$target" ]] || { echo "init-local-env: run without arguments first"; exit 1; }
  if grep -q "^${key}=" "$target"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$target"
  else
    printf '%s=%s\n' "$key" "$value" >> "$target"
  fi
  echo "init-local-env: set $key=$value"
  exit 0
fi

rand() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n'; }

umask 077
created=false
if [[ ! -f "$target" ]]; then
  : > "$target"
  created=true
fi

added=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  if [[ "$line" =~ ^([A-Z0-9_]+)=(.*)$ ]]; then
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    if ! grep -q "^${key}=" "$target"; then
      [[ -z "$value" ]] && value="$(rand)"
      printf '%s=%s\n' "$key" "$value" >> "$target"
      added=$((added + 1))
    fi
  elif $created; then
    printf '%s\n' "$line" >> "$target"
  fi
done < "$example"

if $created; then
  echo "init-local-env: created .env ($added keys, values not shown)."
else
  echo "init-local-env: .env existed; added $added new key(s), existing values unchanged."
fi
