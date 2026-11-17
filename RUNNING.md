# Running ParkSmart locally

Everything you need to start the whole platform on your machine, log in as each kind of user and try every feature.

> **Local development only.** The demo accounts below are created by a seed script on your own machine, use a
> published password, and must never exist in a shared or production environment.

## 1. Prerequisites (once)

| Tool | Notes |
|---|---|
| Docker Desktop | Must be running (Postgres, Kafka, Redis, object storage and the web app run in containers). |
| JDK 21 or newer | Set `JAVA_HOME`, or let the scripts find `C:\Program Files\Java\jdk-21`/`jdk-22`. |
| Node.js 22 | For the web app's dev server, tests and the seed script. |
| Git Bash (Windows) | Run every command below from the **repo root** in Git Bash. |

Once per clone: `git config core.hooksPath .githooks`.

## 2. Start everything

```bash
# Build the 10 backend services (first time, and after backend code changes)
./mvnw clean package -DskipTests

# Infrastructure + the web app container (creates the local env file with generated secrets on the first run)
bash infra/scripts/infra-up.sh --profile web

# The 10 services (about 2 minutes; waits until every one reports UP)
bash infra/scripts/run-local.sh

# Demo accounts and data (safe to re-run)
node web/scripts/seed-demo.mjs
```

Open **http://localhost:3000**. That is the production build (nginx), which forwards API calls to the gateway.

Check it is healthy:

```bash
for p in 8080 8081 8082 8083 8084 8085 8086 8087 8088 8089; do curl -s localhost:$p/actuator/health | head -c 16; echo " :$p"; done
curl -s localhost:3000/healthz
```

## 3. Demo users

All demo accounts use the password **`Demo12345park`**. Emails are already verified.

| Role | Email | Set up with |
|---|---|---|
| Driver | `driver@parksmart.dev` | Name "Dev Driver", a saved car (KA01AB1234, sedan), no bookings |
| Host | `host@parksmart.dev` | Name "Harini Host", profile photo, verified phone, test payout account, the three listings below |
| Admin | `admin@parksmart.dev` | ADMIN role |
| Second driver | `driver2@parksmart.dev` | A paid booking request on the Cubbon Park driveway (tomorrow 10:00 to 12:00), waiting for the host |

The host's listings:

| Listing | State | Booking | Price | Cancellation |
|---|---|---|---|---|
| Covered garage by Indiranagar metro | Live | Instant | ₹60/h | Flexible |
| Driveway near Cubbon Park (host approves) | Live | Host approves each request | ₹40/h | Moderate |
| EV charging bay in Koramangala | In review | Instant | ₹90/h | Strict |

All listings are open 24/7.

### Test cards (payments run in test mode)

Use any future expiry and any CVC.

| Card | Result |
|---|---|
| `4242 4242 4242 4242` | Payment succeeds |
| `4000 0000 0000 0002` | Declined: the booking is released |
| `4000 0000 0000 0341` | Authorizes, then the charge fails at confirmation (booking "Payment failed") |

Any other number is refused while test mode is on. Card details never leave the browser; only a processor token is sent.

## 4. What to try

Use a **separate browser window or incognito window per account**, because one browser shares one login.

**Driver** (`driver@parksmart.dev`)
- Find parking → Bengaluru → open the Indiranagar garage → Reserve → pay with `4242 4242 4242 4242`. The booking confirms within seconds.
- Cancel it from the booking page to see the refund preview (Flexible: full refund up to 1 hour before arrival).
- Book the Cubbon Park driveway to see "Request sent", then accept it as the host.
- Settings: profile photo, phone verification, vehicles, notification preferences. The bell shows booking updates.

**Host** (`host@parksmart.dev`)
- The dashboard shows a waiting request from Second Driver: open it, look at the driver card, then Accept or Decline.
- Your listings: edit details, move the map pin, add photos (drag and drop, set the cover), opening hours, blocked dates, pause or resume.
- New listing: the wizard goes details → photos → opening hours → submit for review.
- Earnings: totals, payout history, payout accounts.

**Admin** (`admin@parksmart.dev`)
- Admin → Listing reviews: open the EV bay and Approve or Reject (rejecting needs a reason, which the host sees).
- Bookings & refunds: paste a booking ID (the long ID in a booking page's URL) to see payment and parties, and issue a goodwill refund.
- Hide a review straight from a listing page ("Hide review" under each review), or by ID.

**New user**: sign up with any email. Emails and SMS are not really sent locally; the codes are written to a log:

```bash
grep -a "DEV ONLY" logs/notification.log | tail -3
```

To make another account an admin: `bash web/scripts/make-admin.sh you@example.com`, then reload the page. An admin cannot
approve their own listings.

**Reviews** need a completed booking. Book the earliest slot on a 24/7 listing. Check in when check-in opens (15 minutes
before arrival), then check out straight away. Both the driver (rating the space) and the host (rating the driver) can then
leave a review. If it says the booking isn't known yet, retry after a few seconds.

## 5. Developer mode (working on the frontend)

```bash
cd web
npm install              # first time
npm run dev:direct-auth  # http://localhost:5173, live reload
```

Compared with :3000 this adds:
- a request/response debug panel;
- a **Dev tools** page (user menu) with the verification codes;
- inline code helpers on the verification screens;
- login and sign-up calls sent straight to Identity, so the gateway's limits don't apply.

Plain `npm run dev` keeps them on the gateway. Other commands: `npm run test`, `npm run build`, `npm run preview`.

After frontend changes, rebuild the container: `docker build -t parksmart/web:local web` and then run
`bash infra/scripts/infra-up.sh --profile web`.

## 6. Stop

```bash
bash infra/scripts/stop-local.sh                   # the 10 services
docker compose -p parksmart stop                    # the containers (data stays in Docker volumes)
```

After a reboot, run `infra-up.sh --profile web` and then `run-local.sh` again. The accounts and data are kept. Re-run the seed
script if you wiped the volumes.

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| "Too many attempts" when logging in or signing up on :3000 | The gateway allows about 5 logins/sign-ups per IP, then 1 every 3 minutes. Wait, stay logged in per window, or use `npm run dev:direct-auth`. |
| "JDK 21+ required" | Open a new Git Bash window or set `JAVA_HOME` to JDK 21+. |
| A service isn't UP | Read `logs/<service>.log`. Re-run `run-local.sh`, which only starts what is down. |
| Page loads, but every action fails | The services aren't running: check health (section 2). |
| A new listing doesn't show in search | Search is updated from events, so allow a few seconds. Search uses the chosen time window and only shows open spaces. |
| "Submit for review" stays disabled | The host needs a profile photo and a verified phone, plus at least one photo and opening hours on the listing. |
| Docker commands fail with a pipe error | Docker Desktop isn't running. |

More detail: [README.md](README.md) (architecture, services, ports) and [web/README.md](web/README.md) (frontend).
