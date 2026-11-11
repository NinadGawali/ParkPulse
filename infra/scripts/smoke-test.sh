#!/usr/bin/env bash
# End-to-end smoke test through the gateway (http://localhost:8080) against the locally running stack
# (infra/scripts/infra-up.sh + infra/scripts/run-local.sh). Needs only bash, curl, sed, docker.
#
# Verification codes: Notification's local profile logs email/SMS bodies (logs/notification.log);
# the script reads the code from there. The admin account is bootstrapped by inserting an ADMIN role
# row into the identity database through the postgres container (local only; there is no public
# way to become admin).
#
# Not covered (marked SKIP): booking completion -> review. Check-in/out need the booking window to
# have started, and the timers that emit BookingCompleted run on real time.
set -uo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
GW="${GATEWAY_URL:-http://localhost:8080}"
NOTIF_LOG="$root/logs/notification.log"
RUN="$(date +%s)"
PASSWORD="Smoke-Test-${RUN}1"
pass=0; failn=0; skipn=0

ok()   { echo "  PASS  $*"; pass=$((pass + 1)); }
bad()  { echo "  FAIL  $*"; failn=$((failn + 1)); }
skip() { echo "  SKIP  $*"; skipn=$((skipn + 1)); }
step() { echo; echo "== $*"; }

# jget <json> <key>: first string/number/bool value of "key" (no jq needed)
jget() {
  printf '%s' "$1" | tr -d '\n' \
    | sed -n "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -n1
}

# req <method> <path> <token|-> [json-body] [extra curl args...]  -> sets $CODE and $BODY
req() {
  local method="$1" path="$2" token="$3" body="${4:-}"; shift 4 2>/dev/null || shift $#
  local args=(-s -m 30 -o /tmp/smoke_body.$$ -w "%{http_code}" -X "$method" "${BASE:-$GW}$path")
  [[ "$token" != "-" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  args+=("$@")
  CODE="$(curl "${args[@]}")"
  BODY="$(cat /tmp/smoke_body.$$ 2>/dev/null)"
}
expect() { # expect <label> <code...>
  local label="$1"; shift
  local c; for c in "$@"; do [[ "$CODE" == "$c" ]] && { ok "$label ($CODE)"; return 0; }; done
  bad "$label: got $CODE, wanted $*: $(printf '%s' "$BODY" | head -c 300)"; return 1
}

# Auth endpoints (register/login/verify) go straight to Identity: the gateway deliberately limits
# them per IP (5 logins burst, then 1 per 3 minutes), which a repeatable smoke test would exhaust.
AUTH="${AUTH_URL:-http://localhost:8081}"
areq() { BASE="$AUTH" req "$@"; }

log_lines() { [[ -f "$NOTIF_LOG" ]] && wc -l < "$NOTIF_LOG" || echo 0; }
# wait_code <kind EMAIL|SMS> <lines-before>: prints the first 6-digit code logged after that line
wait_code() {
  local kind="$1" before="$2" i line code
  for i in $(seq 1 30); do
    line="$(tail -n +"$((before + 1))" "$NOTIF_LOG" 2>/dev/null | grep -a "$kind to" | grep -a 'DEV ONLY body' | head -n1)"
    code="$(printf '%s' "$line" | sed -n 's/.*[^0-9]\([0-9]\{6\}\)[^0-9].*/\1/p; s/.*[^0-9]\([0-9]\{6\}\)$/\1/p' | head -n1)"
    [[ -n "$code" ]] && { echo "$code"; return 0; }
    sleep 1
  done
  return 1
}

register_and_login() { # <email> -> sets TOKEN (also verifies email)
  local email="$1" before code
  before="$(log_lines)"
  areq POST /api/v1/auth/register - "{\"email\":\"$email\",\"password\":\"$PASSWORD\",\"acceptedTermsVersion\":\"2024-01\"}"
  expect "register $email" 201 || return 1
  areq POST /api/v1/auth/login - "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  expect "login $email" 200 || return 1
  TOKEN="$(jget "$BODY" accessToken)"
  code="$(wait_code EMAIL "$before")" || { bad "email code for $email not found in $NOTIF_LOG"; return 1; }
  areq POST /api/v1/auth/email/verify "$TOKEN" "{\"code\":\"$code\"}"
  expect "verify email $email" 204 || return 1
  # re-login so claims reflect the verified account
  areq POST /api/v1/auth/login - "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
  TOKEN="$(jget "$BODY" accessToken)"
}

verify_phone() { # <token> <phone>
  local before code
  before="$(log_lines)"
  areq POST /api/v1/auth/phone/verification-requests "$1" "{\"phone\":\"$2\"}"
  expect "request phone code" 202 || return 1
  code="$(wait_code SMS "$before")" || { bad "SMS code not found in $NOTIF_LOG"; return 1; }
  areq POST /api/v1/auth/phone/verify "$1" "{\"code\":\"$code\"}"
  expect "verify phone" 204
}

# 1x1 PNG
png_file="$(mktemp)"
printf 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==' | base64 -d > "$png_file" 2>/dev/null
png_size="$(wc -c < "$png_file" | tr -d ' ')"

upload_to() { # <uploadUrl json-body> -> PUT the png to the presigned URL
  local url ct
  url="$(jget "$1" uploadUrl)"
  url="${url//\\u0026/&}"; url="${url//&amp;/&}"
  ct="image/png"
  local c; c="$(curl -s -m 30 -o /dev/null -w '%{http_code}' -X PUT -H "Content-Type: $ct" --data-binary "@$png_file" "$url")"
  [[ "$c" == 200 ]] && ok "PUT image to object storage ($c)" || bad "PUT image to object storage: $c"
}

step "0. Health"
for p in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089; do
  h="$(curl -s -m 5 "http://localhost:$p/actuator/health")"
  [[ "$h" == *'"UP"'* ]] && ok "health :$p" || { bad "health :$p ($h)"; echo "stack not up; run infra/scripts/run-local.sh"; exit 1; }
done

step "1. Accounts: host, driver, admin"
HOST_EMAIL="host-$RUN@example.com"; DRIVER_EMAIL="driver-$RUN@example.com"; ADMIN_EMAIL="admin-$RUN@example.com"
register_and_login "$HOST_EMAIL"; HOST_TOKEN="$TOKEN"
req GET /api/v1/accounts/me "$HOST_TOKEN"; HOST_ID="$(jget "$BODY" id)"
register_and_login "$DRIVER_EMAIL"; DRIVER_TOKEN="$TOKEN"
req GET /api/v1/accounts/me "$DRIVER_TOKEN"; DRIVER_ID="$(jget "$BODY" id)"
register_and_login "$ADMIN_EMAIL"; ADMIN_TOKEN="$TOKEN"
req GET /api/v1/accounts/me "$ADMIN_TOKEN"; ADMIN_ID="$(jget "$BODY" id)"
if [[ -n "$ADMIN_ID" ]]; then
  docker exec parksmart-postgres-1 psql -q -U postgres -d identity \
    -c "INSERT INTO roles (account_id, role_type) VALUES ('$ADMIN_ID', 'ADMIN') ON CONFLICT DO NOTHING" >/dev/null 2>&1 \
    && ok "admin role granted via identity DB (local bootstrap)" || bad "could not grant ADMIN role"
  areq POST /api/v1/auth/login - "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$PASSWORD\"}"
  ADMIN_TOKEN="$(jget "$BODY" accessToken)"
fi

step "2. Host: role, phone, profile photo"
req POST /api/v1/accounts/me/roles/host "$HOST_TOKEN"; expect "become host" 200 201 204
areq POST /api/v1/auth/login - "{\"email\":\"$HOST_EMAIL\",\"password\":\"$PASSWORD\"}"; HOST_TOKEN="$(jget "$BODY" accessToken)"
verify_phone "$HOST_TOKEN" "+9198${RUN: -8}"
req PUT /api/v1/profiles/me "$HOST_TOKEN" '{"displayName":"Smoke Host"}'; expect "set display name" 200
req POST /api/v1/profiles/me/photo/upload-urls "$HOST_TOKEN" "{\"contentType\":\"image/png\",\"contentLength\":$png_size}"
if expect "profile photo upload slot" 201; then
  upload_to "$BODY"; UPLOAD_ID="$(jget "$BODY" uploadId)"
  req POST "/api/v1/profiles/me/photo/uploads/$UPLOAD_ID/confirm" "$HOST_TOKEN"; expect "confirm profile photo" 200
fi

step "3. Listing: create, photo, availability, submit"
req POST /api/v1/listings "$HOST_TOKEN" '{"title":"Smoke test garage","description":"Covered spot","address":"1 MG Road, Bengaluru","location":{"lat":12.9716,"lng":77.5946},"timeZone":"Asia/Kolkata","pricePerHour":"50.00","currency":"INR","maxVehicleType":"SUV","amenities":["COVERED"],"instantBook":true,"cancellationPolicy":"MODERATE","bufferMinutes":0}'
expect "create listing" 201 && LISTING_ID="$(jget "$BODY" id)"
req PUT "/api/v1/listings/$LISTING_ID/availability" "$HOST_TOKEN" '{"rules":[{"dayOfWeek":"MONDAY","startTime":"00:00","endTime":"24:00"},{"dayOfWeek":"TUESDAY","startTime":"00:00","endTime":"24:00"},{"dayOfWeek":"WEDNESDAY","startTime":"00:00","endTime":"24:00"},{"dayOfWeek":"THURSDAY","startTime":"00:00","endTime":"24:00"},{"dayOfWeek":"FRIDAY","startTime":"00:00","endTime":"24:00"},{"dayOfWeek":"SATURDAY","startTime":"00:00","endTime":"24:00"},{"dayOfWeek":"SUNDAY","startTime":"00:00","endTime":"24:00"}]}'
expect "set availability" 200
req POST "/api/v1/listings/$LISTING_ID/photos/upload-urls" "$HOST_TOKEN" "{\"contentType\":\"image/png\",\"contentLength\":$png_size}"
if expect "listing photo upload slot" 201; then
  upload_to "$BODY"; PHOTO_ID="$(jget "$BODY" photoId)"
  req POST "/api/v1/listings/$LISTING_ID/photos/$PHOTO_ID/confirm" "$HOST_TOKEN"; expect "confirm listing photo" 200
fi
# host eligibility reaches Listing over Kafka; retry submit briefly
for i in 1 2 3 4 5 6 7 8 9 10; do
  req POST "/api/v1/listings/$LISTING_ID/submit" "$HOST_TOKEN"; [[ "$CODE" == 200 ]] && break; sleep 2
done
expect "submit listing" 200

step "4. Admin BFF moderation queue, approve through BFF"
req GET /admin/v1/moderation/listings "$ADMIN_TOKEN"
expect "moderation queue" 200 && { [[ "$BODY" == *"$LISTING_ID"* ]] && ok "queue contains the submitted listing" || bad "listing not in moderation queue"; }
req GET /admin/v1/moderation/listings "$DRIVER_TOKEN"; expect "moderation queue forbidden for driver" 403
req POST "/admin/v1/moderation/listings/$LISTING_ID/approve" "$ADMIN_TOKEN" '{}'; expect "approve listing via admin-bff" 200
req GET "/api/v1/listings/$LISTING_ID" -; expect "public listing visible" 200 && { [[ "$BODY" == *'"status":"PUBLISHED"'* ]] && ok "listing PUBLISHED" || bad "listing not PUBLISHED"; }

step "5. Search"
found=false
for i in $(seq 1 20); do
  req GET "/api/v1/search/listings?lat=12.9716&lng=77.5946&radiusKm=5" -
  [[ "$BODY" == *"$LISTING_ID"* ]] && { found=true; break; }; sleep 2
done
$found && ok "search finds the listing (projection built from events)" || bad "listing not found in search"

step "6. Driver books and pays"
START="$(date -u -d '+3 days' +%Y-%m-%dT10:00:00Z)"; END="$(date -u -d '+3 days' +%Y-%m-%dT12:00:00Z)"
IDEM="smoke-$RUN"
req POST /api/v1/bookings "$DRIVER_TOKEN" "{\"listingId\":\"$LISTING_ID\",\"startTime\":\"$START\",\"endTime\":\"$END\"}" -H "Idempotency-Key: $IDEM"
expect "create booking" 202 && BOOKING_ID="$(jget "$BODY" id)"
[[ "$(jget "$BODY" status)" == PENDING_PAYMENT ]] && ok "booking PENDING_PAYMENT" || bad "status $(jget "$BODY" status)"
req POST /api/v1/bookings "$DRIVER_TOKEN" "{\"listingId\":\"$LISTING_ID\",\"startTime\":\"$START\",\"endTime\":\"$END\"}" -H "Idempotency-Key: $IDEM"
expect "idempotent replay" 200
req POST /api/v1/bookings "$DRIVER_TOKEN" "{\"listingId\":\"$LISTING_ID\",\"startTime\":\"$START\",\"endTime\":\"$END\"}"
expect "missing Idempotency-Key rejected" 400
req POST /api/v1/bookings "$HOST_TOKEN" "{\"listingId\":\"$LISTING_ID\",\"startTime\":\"$START\",\"endTime\":\"$END\"}" -H "Idempotency-Key: host-$IDEM"
expect "host cannot book own listing" 422 403
INTENT_ID=""
for i in $(seq 1 20); do
  req GET "/api/v1/payments/intents?bookingId=$BOOKING_ID" "$DRIVER_TOKEN"
  [[ "$CODE" == 200 ]] && { INTENT_ID="$(jget "$BODY" id)"; break; }; sleep 1
done
[[ -n "$INTENT_ID" ]] && ok "payment intent created from BookingRequested" || bad "no intent for booking"
req POST "/api/v1/payments/intents/$INTENT_ID/authorize" "$DRIVER_TOKEN" '{"paymentMethodToken":"fake_ok"}' -H "Idempotency-Key: pay-$IDEM"
expect "authorize payment (fake_ok)" 200 && { [[ "$(jget "$BODY" status)" == AUTHORIZED ]] && ok "intent AUTHORIZED" || bad "intent status $(jget "$BODY" status)"; }
confirmed=false
for i in $(seq 1 20); do
  req GET "/api/v1/bookings/$BOOKING_ID" "$DRIVER_TOKEN"
  [[ "$(jget "$BODY" status)" == CONFIRMED ]] && { confirmed=true; break; }; sleep 1
done
$confirmed && ok "booking CONFIRMED after PaymentAuthorized" || bad "booking status $(jget "$BODY" status)"
req GET "/api/v1/bookings/$BOOKING_ID" "$HOST_TOKEN"; expect "host can read the booking" 200
req GET "/admin/v1/bookings/$BOOKING_ID" "$ADMIN_TOKEN"; expect "admin-bff booking view" 200
req POST "/admin/v1/bookings/$BOOKING_ID/refunds" "$DRIVER_TOKEN" '{"amount":"10.00","reason":"smoke"}' -H "Idempotency-Key: rf-d-$IDEM"; expect "refund forbidden for driver (gateway admin-bff-refund route)" 403
req POST "/admin/v1/bookings/$BOOKING_ID/refunds" "$ADMIN_TOKEN" '{"amount":"10.00","reason":"smoke goodwill refund"}' -H "Idempotency-Key: rf-a-$IDEM"; expect "admin partial refund via admin-bff" 200

step "7. Notifications"
got=false
for i in $(seq 1 20); do
  req GET /api/v1/notifications "$DRIVER_TOKEN"
  [[ "$CODE" == 200 && "$BODY" == *'"id"'* ]] && { got=true; break; }; sleep 1
done
$got && ok "driver in-app notification feed has entries" || bad "driver feed empty: $BODY"
req GET /api/v1/notifications/unread-count "$HOST_TOKEN"; expect "host unread count" 200

step "8. Reviews"
skip "booking completion -> review: needs the booking window (3 days ahead) to elapse; covered by Review/Booking integration tests"
req GET "/api/v1/reviews?targetType=LISTING&targetId=$LISTING_ID" -; [[ "$CODE" =~ ^(200|400|404)$ ]] && ok "review service reachable through gateway ($CODE)" || bad "review route: $CODE"

rm -f "$png_file" /tmp/smoke_body.$$
echo; echo "Result: $pass passed, $failn failed, $skipn skipped"
[[ $failn -eq 0 ]]
