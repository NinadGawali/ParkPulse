# ParkSmart web test console

A React 18 + TypeScript + Vite app for manually exercising every ParkSmart flow in a browser against the
local stack. It is a **test console, not a product front end**: tokens live in `localStorage`, request bodies
are shown in a debug panel, and a dev-only helper reads the Notification log. Never point it at a shared or
production environment.

## Start

```bash
# 1. stack (repo root, Git Bash): see "Running locally" in ../README.md
bash infra/scripts/infra-up.sh && bash infra/scripts/run-local.sh      # skip if already running
# 2. console
cd web
npm install
npm run dev                  # http://localhost:5173
# or, to bypass the gateway's per-IP login/register limits (see below):
npm run dev:direct-auth
```

`npm run build` type-checks (`tsc` strict) and builds `dist/`. `npm run test` runs Vitest (client, helpers, components).
`node scripts/e2e-proxy.mjs [--complete]` runs an end-to-end check of the whole stack **through the dev proxy**.

## How it talks to the backend

The Vite dev server proxies, so the gateway needs no CORS changes:

| Browser path | Goes to |
|---|---|
| `/api/**`, `/admin/v1/**`, `/.well-known/**` | gateway `http://localhost:8080` |
| `/_storage/**` | object storage `http://localhost:9000` (presigned upload URLs are rewritten to this; storage sends no CORS headers) |
| `/__dev/codes` | dev-server middleware (`vite.config.ts`) that parses `logs/notification.log` (dev server only, never in a build) |

Only `/admin/v1` is proxied, not `/admin`: the console has its own `/admin/moderation` etc. routes.

Gateway rate limits: login is limited to ~5 per IP then one per 3 minutes, register and code requests ~5 per
hour. A multi-role test session hits that fast (the UI shows a hint on 429). `npm run dev:direct-auth` sends
`/api/v1/auth/*` straight to Identity `:8081`, exactly like `infra/scripts/smoke-test.sh`; everything else still
goes through the gateway. Env overrides: `GATEWAY_URL`, `IDENTITY_URL`, `STORAGE_URL`.

## What is covered (role-aware menu)

- **Anyone**: search (geo + filters + time window, cursor paging, Leaflet/OpenStreetMap map; map tiles need internet), listing detail with reviews, public reviews, Dev helper.
- **Auth**: register, login, logout, transparent refresh-token rotation (single-flight, proactive before expiry, retry on 401), email and phone verification, forgot/reset password, become host (rotates the token so the JWT carries HOST).
- **Profile** (all): display name, bio, city, photo (presigned PUT then confirm, three steps shown), notification preferences. **Vehicles** (driver): CRUD, primary. **Payout account** (host).
- **Host**: create/edit listing (map picker, price, amenities, instant vs request, cancellation policy, buffer; PATCH with `If-Match`), photos, weekly availability (24/7 preset), blackouts, submit, pause, resume, archive, status list; booking requests with approve/decline, host cancel; earnings and payouts.
- **Driver**: book with `Idempotency-Key` (same body = same key = replay `200`; changed body = new key), pay with the fake processor, my bookings with status polling, cancel with a refund-tier preview, check-in/out, reviews.
- **Reviews**: both directions from the completed booking page; listing/driver lists and summaries.
- **Notifications**: feed, unread badge (polled every 20 s), mark read / read all.
- **Admin**: moderation queue (approve/reject), booking case, manual refund with `Idempotency-Key`, hide review. A page lists what has **no backend endpoint**: account suspend, disputes, payout holds (see `docs/PROGRESS.md`, phase 10 gaps).
- Every failed call renders an RFC 7807 problem (status, title, detail, type, correlationId, field errors); a collapsible debug panel at the bottom shows the last requests and responses (secrets such as passwords and tokens are redacted).

The public booking quote is an **internal** endpoint (`/internal/**` is never routed by the gateway), so the console shows a
client-side estimate; Booking fetches the authoritative quote itself.

## Verification codes (local)

Email and SMS are "sent" by Notification writing them to `logs/notification.log` (`DEV ONLY body`, addresses masked). The in-app
feed redacts codes on purpose. The console's **Dev helper** page (and the Account / password pages) lists the newest codes
by reading that file through the dev server. Password reset uses the opaque token logged the same way (not a 6-digit code).
By hand: `grep -a "DEV ONLY" logs/notification.log | tail`.

## Becoming ADMIN (local only)

There is no public way. `infra/scripts/smoke-test.sh` inserts an `ADMIN` row into the identity database through the postgres
container; `web/scripts/make-admin.sh` does the same for one existing account:

```bash
bash web/scripts/make-admin.sh you@example.com     # from the repo root, Git Bash
```

Then log out and in again (roles are baked into the JWT). It validates the email, talks only to the local docker container
(`PARKSMART_POSTGRES_CONTAINER`, default `parksmart-postgres-1`), upgrades an existing account and never creates one. Admins cannot
approve their own listings, so use a second account as the host.

## Reaching COMPLETED and reviews fast

Booking's rules (from `Booking.java`): start on a 15-minute boundary and at least 15 minutes from now; duration 30 minutes to 30 days;
**check-in is allowed from 15 minutes before the start until 30 minutes after**; **check-out has no time gate** and moves
`CHECKED_IN` to `COMPLETED` at once. So no config change or backdoor is needed, and the wait is at most about 15 minutes:

1. Host publishes a listing with the **24/7** availability preset.
2. Driver opens it and clicks **Soonest slot (fast lane)** (a 30-minute window on the next 15-minute boundary that is 17+ minutes away). Book, then pay with `fake_ok` (request-to-book listings also need the host to approve).
3. The booking page shows a countdown to when check-in opens, then enables **Check in**; click **Check out** straight away.
4. Booking emits `BookingCompleted`; Review learns it over Kafka (a 404 on submit for a few seconds means "not seen yet": retry). Driver reviews the listing, host reviews the driver; duplicates are 409.

Left alone, the timers (30 s interval, `parksmart.booking.timers.interval`) complete a checked-in booking at its end time and mark a missed
check-in `NO_SHOW` 30 minutes after the start. The same steps are in the UI under Dev helper.

## Fake payment tokens

`fake_ok` authorizes (capture on confirmation). `fake_decline` fails with `card_declined` (intent FAILED, booking EXPIRED; book again).
`fake_capture_fail` authorizes but the capture is rejected on confirmation (booking PAYMENT_FAILED).

## Tests

- `npm run test` (Vitest + jsdom): API client (refresh rotation, single-flight, proactive refresh, auth loss, problem parsing, idempotency keys incl. reuse across a 401 retry, redaction), helpers (fast-lane slot, refund tiers, storage URL rewrite), components (problem display, role-aware menu, debug panel).
- `node scripts/e2e-proxy.mjs [--complete]` (needs the dev server, preferably `dev:direct-auth`, and the running stack): ~80 assertions through the proxy: register, verify, host, listing, approve, search, book, replay, pay, decline, cancel, notifications, admin case/refund, password reset. `--complete` also waits for the check-in window, then check-in, check-out, both reviews, duplicate/stranger rejections and admin hide.

## Layout

```
src/api/        client.ts (fetch wrapper), endpoints.ts (typed calls per service), types.ts (from docs/openapi)
src/state/      auth context (JWT claims, roles), debug log store
src/components/ layout/nav, problem + debug panels, forms, Leaflet map, uploader, dev codes
src/pages/      one file per area (auth, account, profile, host listings, bookings, search, admin, misc, dev helper)
scripts/        make-admin.sh, e2e-proxy.mjs
```
