# ParkSmart web app

The ParkSmart front end for drivers, hosts and admins: React 18 + TypeScript (strict) + Vite + React Router, plain CSS
(design tokens in `src/styles.css`), Leaflet maps. It talks to the platform only through the gateway, on the same origin
(a proxy in development, nginx in production), so the gateway needs no CORS.

## What's in it

| Area | Pages |
|---|---|
| Everyone | Home (city + time search, featured spaces), search (list + map with price pins, "search this area", filters in the URL), listing page (gallery, amenities, opening hours, policy, map, reviews, host), help, terms, privacy |
| Account | Sign up (with email-code verification step), log in, forgot/reset password, settings: profile + photo, email/phone verification, vehicles, notification preferences, notification bell + page |
| Drivers | Reserve widget (15-minute grid, live total, vehicle size check), checkout with card form and hold countdown, booking page with progress timeline, check-in/out, directions, cancel with refund preview, reviews; My bookings |
| Hosts | Become a host, dashboard (setup checklist, earnings, requests, upcoming), listing wizard → photos (drag and drop, cover photo) → opening hours → submit, pause/resume/archive, blocked dates, booking requests (accept/decline), earnings + payout accounts |
| Admins | Listing review queue → preview with approve/reject bar, booking support case with goodwill refund, hide reviews (also inline on listing pages) |

Every API error is shown as a friendly message built from the RFC 7807 problem (field errors, and the correlation ID as a support reference).

## Run it

```bash
# backend (repo root, Git Bash): see "Running locally" in ../README.md
bash infra/scripts/infra-up.sh && bash infra/scripts/run-local.sh

cd web
npm install
npm run dev               # http://localhost:5173 (dev server with proxy and dev tools)
npm run dev:direct-auth   # same, but /api/v1/auth/* goes straight to Identity (skips the per-IP login limit)
npm run build             # strict type-check + production bundle in dist/
npm run preview           # serve dist/ on :4173 with the same proxy
npm run test              # Vitest
```

### Production image (nginx)

```bash
docker compose --env-file .env -f infra/docker-compose.yml --profile web up -d --build web   # http://localhost:3000
```

`web/Dockerfile` builds the bundle and serves it from `nginxinc/nginx-unprivileged` (non-root, `HEALTHCHECK` on `/healthz`).
nginx serves the SPA (hashed assets cached for a year, `index.html` never), proxies `/api`, `/admin/v1` and `/.well-known` to
the gateway, and sets security headers including a Content-Security-Policy. Runtime settings (environment variables):

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_URL` | `http://gateway:8080` | Gateway upstream (compose uses `host.docker.internal:8080`, since services run on the host locally) |
| `STORAGE_URL`, `STORAGE_SIGNED_HOST` | `http://object-storage:9000`, `localhost:9000` | Local S3 stand-in for presigned uploads via `/_storage` (signature covers the Host header) |
| `MEDIA_ORIGINS` | `http://localhost:9000` | Where photos are served from (CSP `img-src`); a CloudFront origin on AWS |
| `UPLOAD_ORIGINS` | empty | Extra CSP `connect-src` origins, e.g. the S3 bucket origin for direct presigned uploads on AWS |
| `MAP_TILE_ORIGIN` | `https://tile.openstreetmap.org` | Map tile origin (CSP) |

Build arguments: `VITE_TEST_MODE` (default `true`: test-mode banner, test cards, test payout accounts; set `false` with a real
payment processor) and `VITE_MAP_TILE_URL` (OpenStreetMap's public tiles are for light use only; use a tile provider in production).

On AWS the same image can run on ECS behind the ALB (or the `dist/` bundle can go to S3 + CloudFront with path routing for
`/api/*`). The gateway trusts `X-Forwarded-For` under the `aws` profile, which is right only when nothing but the ALB/nginx can
reach it.

## How things work

- **Session**: the access token is kept in memory only; the refresh token is in `localStorage` so a reload or new tab resumes the
  session with one refresh call (a splash waits for it). Refresh rotation is single-flight and proactive before expiry; logging out
  in one tab ends the others. Moving the refresh token to an httpOnly cookie is a phase-11 hardening item (needs Identity changes).
- **Booking**: the reserve widget mirrors Booking's rules (15-minute grid, at least 15 minutes ahead, 30 min to 30 days) to explain
  them up front; the server stays authoritative. The total is an estimate (hours × price); Booking prices from its own quote.
  Each distinct request gets one `Idempotency-Key`, so a double click or retry replays the same booking.
- **Payments (test mode)**: card details never leave the browser; the form turns a test card into the FakeProcessor token and only
  the token is sent, as with a real tokenizing processor. `4242 4242 4242 4242` succeeds, `4000 0000 0000 0002` is declined,
  `4000 0000 0000 0341` authorizes and then fails to capture. Any future expiry and any CVC.
- **Eventual consistency**: payment intents, review eligibility and search results arrive over Kafka. The UI polls while a booking
  can still change, retries the payment-intent lookup until it exists, and says "try again in a few seconds" where that applies.
- **Lazy loading**: the home page and auth are in the main bundle; search, listing, bookings, host, settings and admin (and Leaflet)
  load on demand.

## Development tools (dev server only)

`npm run dev` adds a request/response debug panel at the bottom, a **Dev tools** page (`/dev`, in the user menu) and inline
"codes from logs/notification.log" helpers on the verification and password-reset screens, served by a dev-server middleware
(`/__dev/codes`). None of these exist in `npm run build` output (checked: no dev strings in `dist/`).

- Verification and reset codes are "sent" by Notification writing them to `logs/notification.log`; the in-app feed redacts them.
- Demo users and data: `node web/scripts/seed-demo.mjs` (accounts and passwords in [../RUNNING.md](../RUNNING.md)).
- ADMIN locally: `bash web/scripts/make-admin.sh you@example.com`, then log out and in. Admins cannot approve their own listings.
- Reaching COMPLETED fast: publish a listing with 24/7 hours, book the soonest slot, pay with 4242…, check in when it opens
  (15 minutes before arrival), check out at once; both sides can then review.
- `node scripts/e2e-proxy.mjs [--complete]` runs ~90 API assertions through the dev proxy (register → verify → host → listing →
  approve → search → book → pay → cancel/complete → reviews → admin).

## Tests

`npm run test`: API client (refresh rotation, single-flight, proactive refresh, auth loss, problem parsing, idempotency keys,
redaction), session storage (access token never persisted), helpers (booking window rules, refund tiers, test cards, Luhn/expiry,
money and duration formatting, storage URL rewrite), components (problem display, status labels, header by role, route guards,
debug panel).

## Layout

```
src/api/         client.ts (fetch wrapper, refresh, problems), endpoints.ts (typed calls), types.ts (from docs/openapi), index.ts (session store)
src/state/       auth (session bootstrap, roles), cache (listing/profile lookups), debug log
src/components/  Layout (header, menus, footer, guards), ui (primitives, hooks, toasts), Icon, LeafletMap, WhenPicker, ListingCard, ListingForm, PhotoUploader, dev tools
src/pages/       Home, Search, Listing, Booking(s), Host, ListingManage, Settings, Admin, Auth, Misc, Dev
nginx/           production server config templates
```
