# ParkSmart — Claude Code guide

Parking marketplace (Airbnb-for-parking) on Spring Boot microservices. Hosts list private spaces; drivers search, book, pay, park, review.

**Session start: read docs/PROGRESS.md. Session end: update it** (status, decisions, open questions). It is the memory between `/clear`ed sessions.

## Source of truth
1. docs/ParkSmart-Services-and-Entities.md — authoritative entities, ownership, schema (section 5)
2. docs/ParkSmart-Architecture-Review.md — rationale, APIs, events, saga
If they disagree, Services-and-Entities wins; record the resolution in the PROGRESS.md decisions log.

## Stack (fixed)
- Java 21, Spring Boot 3.5.x, Spring Cloud 2025.0.x, Maven monorepo (root pom + one module per service)
- PostgreSQL + PostGIS, one DB per service, Flyway owns the schema (V1, V2, ... never edit an applied one)
- Persistence: Spring Data JPA (Hibernate, `ddl-auto: validate`) with `@Entity` classes only in `infrastructure.persistence`, mapped by hand to the pure domain; `@Version` for optimistic locking. Use native SQL / `JdbcClient` where JPA is awkward (outbox, processed_events, tstzrange exclusion constraints, PostGIS, conditional `UPDATE ... WHERE status = ?`). `services/profile` is the JPA reference; `services/identity` predates this and stays on `JdbcClient`.
- Kafka (KRaft), Redis (cache, rate limit), Spring Cloud Gateway
- Spring Security + JWT RS256: Identity signs, everyone else verifies via JWKS
- Search: OpenSearch behind an interface (PostGIS-backed implementation allowed first)
- Tests: JUnit 5, Mockito, AssertJ, Testcontainers (`postgis/postgis:17-3.7`), contract tests, ArchUnit
- Layout per service: `domain / application / infrastructure / api`; domain has zero Spring/JPA/Jackson imports
- Package root: `com.parksmart.<service>`; formatting: Spotless (google-java-format), checked in `verify`

## Hard rules
1. **Commits**: no `Co-Authored-By`, no "Generated with Claude Code", no Claude/Anthropic attribution in commits or PRs. Conventional Commits, small and focused. Never push, never force-push, never rewrite history unless asked. (`.githooks/commit-msg` strips attribution as a backstop.)
2. **No secrets in the repo.** Only `.env.example`. Never read or edit real `.env` files (a hook blocks it).
3. **No service imports another service's code.** Shared code only in `shared/common-events` (event schemas), `shared/common-web` (errors, pagination), `shared/common-security` (JWT). No shared entities.
4. **No cross-service SQL or foreign keys.** Same-request answer → REST call. Eventual consistency OK → consume an event.
5. **Double-booking impossible at the DB level**: exclusion constraint on `tstzrange` (needs `btree_gist`), not only Java checks.
6. **Booking is a saga**: `PENDING_PAYMENT → CONFIRMED` only after `PaymentAuthorized`. `Idempotency-Key` header required on booking creation.
7. **Ownership in the service layer** on every mutating endpoint, with a test proving a non-owner gets 403.
8. **DTOs at the API boundary only**; entities never leave a service. Bean Validation on every request DTO. Errors are RFC 7807 `ProblemDetail`.
9. **Kafka**: transactional outbox for publishing; idempotent consumers (processed-event table or natural idempotency); every consumer has a `.DLT` dead-letter topic.
10. **Ambiguity**: ask one batched set of questions, then proceed. Don't re-ask what the docs decide.

## AWS-readiness (build for it, don't build infra yet)
- 12-factor: all config via env vars / `${PLACEHOLDER}` in application.yml; no hardcoded hosts or secrets
- Profiles: `local` (docker-compose), `test` (Testcontainers), `aws` (Secrets Manager-injected env vars)
- Each service: multi-stage Dockerfile, non-root, HEALTHCHECK, `/actuator/health`
- Structured JSON logs; correlation ID propagated gateway → services → Kafka headers
- Photos: presigned-URL uploads behind an interface (local = S3-compatible container, AWS = S3 + CloudFront)
- Payments: `PaymentProcessor` interface; `FakeProcessor` now, Razorpay/Stripe adapter later
- Target: ECS Fargate/EKS, RDS (PostGIS), MSK, ElastiCache, OpenSearch Service, SES, Secrets Manager, ALB

## Module map
| Path | Purpose | Status |
|---|---|---|
| `shared/common-events` | Event envelope, catalog, 38 versioned payload records + JSON Schemas | done |
| `shared/common-web` | ProblemDetail handling, cursor paging, correlation ID | done |
| `shared/common-security` | RS256 JWT validation, roles, CurrentAccount, 401/403 problems | done |
| `services/gateway` (:8080) | Spring Cloud Gateway: routing, edge JWT check, Redis rate limits | done |
| `services/identity` (:8081) | Accounts, roles, tokens, verification (reference service) | done |
| `tools/contract-validation` | Build gate: OpenAPI contracts parse and follow conventions | done |
| `services/profile` (:8082) | Profiles, photos, vehicles, payout tokens, notification prefs (JPA reference) | done |
| `services/listing` (:8083) | Listings, photos, availability, moderation, host-eligibility and active-booking projections, booking quote | done |
| `services/booking` (:8084) | Booking state machine, exclusion constraint, idempotent create, saga + timers (JdbcClient, no JPA) | done |
| `services/payment` | Intents, ledger, commission rules, payouts | planned (phase 6) |
| `services/search` | Event-built geo search projection | planned (phase 7) |
| `services/notification` | Email/in-app delivery, templates | planned (phase 8) |
| `services/review` | Reviews, eligibility projection, aggregates | planned (phase 9) |
| `services/admin-bff` | Composed admin views, no entities | planned (phase 10) |
| `infra/` | docker-compose for local infra | done |
| `docs/` | Design docs, PROGRESS.md, openapi/, plans/ | ongoing |

New service: `/new-service <name>`. Verify: `/verify-service <name>`. Close a phase: `/phase-done <n>`.

## Build and run
- JDK 21+ required (`JAVA_HOME`). Git Bash: `./mvnw ...`; PowerShell: `.\mvnw.cmd ...`
- One service: `./mvnw -pl services/<name> -am verify`
- Everything: `./mvnw verify`  ·  Fix formatting: `./mvnw spotless:apply`
- Local infra: `cp .env.example .env` (fill values), then `docker compose --env-file .env -f infra/docker-compose.yml up -d` (add `--profile search` for OpenSearch)
- Docker Desktop must be running for Testcontainers tests. `*Test` = unit (surefire), `*IT` = integration (failsafe, runs in `verify`)
- Run a service locally: `java -jar services/<name>/target/<name>-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local` from the repo root (reads the root env file). Ports: gateway 8080, identity 8081, profile 8082, listing 8083, booking 8084, payment 8085, search 8086

## Work order per service
Flyway migration → domain model + state machine + unit tests → repository + Testcontainers tests → application service → controller + validation + ProblemDetail → outbox events → ownership/403 tests → contract tests → Dockerfile.

## Definition of done (per service)
- `./mvnw -pl services/<name> -am verify` green; never commit a red build
- Migrations apply on a clean Postgres (Testcontainers)
- All hard rules satisfied; OpenAPI in `docs/openapi/<name>.yaml` matches the implementation
- `architect` and `security-reviewer` subagents report no blockers
- README section + docs/PROGRESS.md updated; commits clean (no attribution trailers)

## Subagents (.claude/agents)
`architect` (read-only design review) · `service-builder` (one service end-to-end) · `db-migration` (Flyway + PostGIS) · `test-writer` · `security-reviewer` (read-only) · `devops` (Docker, compose, CI, later Terraform) · `code-reviewer` (read-only final pass).
Parallelize independent services in separate git worktrees (`git worktree add ../parksmart-<svc> -b feat/<svc>`). Booking and Payment are built sequentially because they share the saga.

## Style
Boring, standard Spring. No custom frameworks, no speculative abstractions, no services beyond the ten in the docs. Inject `Clock` for time. Money is `BigDecimal` / `NUMERIC`. Times are `Instant` / `TIMESTAMPTZ`.
