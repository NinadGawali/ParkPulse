#!/bin/bash
# Creates one database and one login role per service on first container start.
# Each role can connect only to its own database (database-per-service rule).
set -euo pipefail

create_db() {
  local name="$1" password="$2" extensions="$3"
  echo "Creating database ${name} owned by role ${name}_app"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v role="${name}_app" -v pwd="${password}" -v db="${name}" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'pwd';
CREATE DATABASE :"db" OWNER :"role";
REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"role";
SQL
  for ext in $extensions; do
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$name" \
         -c "CREATE EXTENSION IF NOT EXISTS ${ext};"
  done
}

create_db identity     "$IDENTITY_DB_PASSWORD"     ""
create_db profile      "$PROFILE_DB_PASSWORD"      ""
create_db listing      "$LISTING_DB_PASSWORD"      "postgis"
# btree_gist is required by the bookings exclusion constraint (listing_id WITH =, time_range WITH &&).
create_db booking      "$BOOKING_DB_PASSWORD"      "btree_gist"
create_db payment      "$PAYMENT_DB_PASSWORD"      ""
create_db review       "$REVIEW_DB_PASSWORD"       ""
create_db notification "$NOTIFICATION_DB_PASSWORD" ""
# Fallback store for Search Service when OpenSearch is not used.
create_db search       "$SEARCH_DB_PASSWORD"       "postgis"
