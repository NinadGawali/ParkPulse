#!/usr/bin/env bash
# LOCAL DEVELOPMENT ONLY. Grants the ADMIN role to an existing account by inserting a row into the
# identity database of the docker-compose postgres container, exactly like infra/scripts/smoke-test.sh.
# There is deliberately no public API for this.
#
# Usage (Git Bash, from anywhere):  web/scripts/make-admin.sh <email>
# Then log out and log in again in the console (roles are baked into the JWT).
#
# Safety: talks only to a local docker container (default parksmart-postgres-1, override with
# PARKSMART_POSTGRES_CONTAINER); the email must look like an email, so nothing odd reaches SQL; it never
# reads the real env file; it refuses to create accounts, it only upgrades one that exists.
set -euo pipefail

email="${1:-}"
if [[ -z "$email" ]]; then
  echo "usage: $0 <email>" >&2
  exit 2
fi
if ! [[ "$email" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
  echo "make-admin: '$email' does not look like a plain email address" >&2
  exit 2
fi

container="${PARKSMART_POSTGRES_CONTAINER:-parksmart-postgres-1}"
if ! docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null | grep -q true; then
  echo "make-admin: container '$container' is not running (start it with infra/scripts/infra-up.sh)" >&2
  exit 1
fi

psql_identity() {
  MSYS_NO_PATHCONV=1 docker exec "$container" psql -q -t -A -U postgres -d identity "$@"
}

id="$(psql_identity -c "SELECT id FROM accounts WHERE email_normalized = lower('$email') AND deleted_at IS NULL")"
if [[ -z "$id" ]]; then
  echo "make-admin: no account with email $email in the local identity database (register it first)" >&2
  exit 1
fi

psql_identity -c "INSERT INTO roles (account_id, role_type) VALUES ('$id', 'ADMIN') ON CONFLICT DO NOTHING" >/dev/null
roles="$(psql_identity -c "SELECT string_agg(role_type, ', ' ORDER BY role_type) FROM roles WHERE account_id = '$id'")"
echo "make-admin: $email ($id) now has roles: $roles"
echo "make-admin: log out and log in again so the new token carries ADMIN."
