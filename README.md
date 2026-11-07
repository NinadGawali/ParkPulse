# ParkSmart

A parking marketplace (Airbnb-for-parking): hosts list private parking spaces; drivers search, book, pay, park, and review. Built as Spring Boot microservices in a Maven monorepo. (Repository name: ParkPulse.)

**Status:** Phases 0–6 done (bootstrap, contracts, Gateway + Identity, Profile, Listing, Booking, Payment). See [docs/PROGRESS.md](docs/PROGRESS.md).

## Design docs
- [Services & Entities](docs/ParkSmart-Services-and-Entities.md): authoritative service boundaries, entities, schema
- [Architecture Review](docs/ParkSmart-Architecture-Review.md): rationale, APIs, events, booking saga, security
- [Vertical slice plan](docs/plans/01-vertical-slice.md) and the OpenAPI contracts in [docs/openapi](docs/openapi)

## Prerequisites
- JDK 21+ (`JAVA_HOME` must point to it)
- Docker Desktop (local infra and Testcontainers)
- Maven is optional; use the wrapper (`./mvnw` in Git Bash, `.\mvnw.cmd` in PowerShell)

## First-time setup
```bash
git config core.hooksPath .githooks       # enables the commit-msg hook
bash infra/scripts/init-local-env.sh      # creates .env with random local-only passwords
```

## Build
```bash
./mvnw verify                             # everything (unit + Testcontainers integration tests)
./mvnw -pl services/<name> -am verify     # one service and what it depends on
./mvnw spotless:apply                     # fix formatting
```

## Local infrastructure
```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker compose --env-file .env -f infra/docker-compose.yml --profile search up -d   # with OpenSearch
docker compose --env-file .env -f infra/docker-compose.yml down
```

| Component | Port | Notes |
|---|---|---|
| Postgres 17 + PostGIS 3.7 | 5432 | One DB + role per service: identity, profile, listing, booking, payment, review, notification, search |
| Kafka 4.1 (KRaft) | 9092 | Auto topic creation off; services declare their topics and `.DLT` topics |
| Redis 8.6 | 6380 | Password from `.env` (6380 avoids clashing with a host Redis on 6379) |
| RustFS (S3-compatible) | 9000 / 9001 | Local stand-in for S3; console on 9001 |
| OpenSearch 2.19 | 9200 | Only with `--profile search` |

## Services
Run any service from the repo root with the `local` profile (reads `.env`):
```bash
java -jar services/<name>/target/<name>-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

| Service | Port | What it does |
|---|---|---|
| gateway | 8080 | Single entry point: routing, edge JWT check, Redis rate limits, correlation IDs; blocks `/internal/**` |
| identity | 8081 | Accounts, login, refresh-token rotation, email/phone verification, roles, JWKS. Locally, verification codes are logged ("DEV ONLY") until Notification exists |
| payment | 8085 | Payment intents created from BookingRequested; driver authorizes with a processor token (FakeProcessor: `fake_ok`, `fake_decline`, `fake_capture_fail`); capture on confirmation, void on expiry/decline, policy refunds on cancellation; double-entry ledger with 15% commission; weekly host payouts and earnings |
| booking | 8084 | Bookings: idempotent create (holds the slot; a Postgres exclusion constraint makes overlaps impossible), the payment saga (PaymentAuthorized → CONFIRMED or host approval), cancellations with policy refunds, check-in/out, and timers for payment expiry, host deadline, no-show and completion |
| listing | 8083 | Listings (DRAFT → review → PUBLISHED ↔ PAUSED, ARCHIVED), photos, weekly availability and blackouts, admin moderation, and the internal booking quote. Tracks host eligibility (verified phone, profile photo, suspension) and active bookings from events |
| profile | 8082 | Profiles and presigned photo uploads, vehicles, host payout accounts, notification preferences. Consumes `AccountRegistered` and driver ratings; publishes `ProfileUpdated`, `VehicleAdded`, `VehicleRemoved` |

Contracts: [identity](docs/openapi/identity.yaml), [profile](docs/openapi/profile.yaml), [listing](docs/openapi/listing.yaml), [booking](docs/openapi/booking.yaml), [payment](docs/openapi/payment.yaml).

### Profile photo upload (through the gateway)
1. `POST /api/v1/profiles/me/photo/upload-urls {"contentType":"image/png","contentLength":12345}` returns `uploadId`, a presigned `uploadUrl` (10 min) and `requiredHeaders`.
2. `PUT` the bytes to `uploadUrl` with those headers (directly to object storage).
3. `POST /api/v1/profiles/me/photo/uploads/{uploadId}/confirm`: the service checks size, type and the image signature, sets the photo, and emits `ProfileUpdated{hasPhoto=true}`.

## Layout
```
pom.xml                 parent POM (Boot 3.5, Spring Cloud 2025.0, Spotless, enforcer)
shared/common-events    event envelope, catalog, payload records, JSON Schemas
shared/common-web       ProblemDetail handling, pagination, correlation ID
shared/common-security  JWT resource-server support, roles, CurrentAccount
services/               one module per service
tools/                  build-time contract validation
infra/                  docker-compose, Postgres init, local env script
docs/                   design docs, PROGRESS.md, openapi/, plans/
.claude/                Claude Code settings, hooks, subagents, slash commands
.githooks/              commit-msg hook
```

## Working with Claude Code
See [CLAUDE.md](CLAUDE.md). Slash commands: `/new-service <name>`, `/verify-service <name>`, `/phase-done <n>`.
