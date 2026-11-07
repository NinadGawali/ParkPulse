# ParkSmart — Progress

Read at session start, update at session end. This file is the memory between sessions.

## Current status
- **Phase 0 (bootstrap): complete, awaiting review** on branch `chore/phase-0-bootstrap` (not merged, not pushed).
- Next: **Phase 1 — contracts** (OpenAPI for Identity/Listing/Booking/Payment, event schemas in `shared/common-events`, `docs/plans/01-vertical-slice.md`), in plan mode, reviewed by `architect` before approval.

## Phase checklist
- [x] 0. Bootstrap: Claude Code settings, commit-msg hook, CLAUDE.md, PROGRESS.md, subagents, slash commands, hooks, Maven skeleton, docker-compose
- [ ] 1. Contracts: OpenAPI (Identity, Listing, Booking, Payment), event envelope + schemas + records, vertical-slice plan
- [ ] 2. Gateway + Identity (JWT RS256, roles, refresh rotation, verification tokens, auth rate limiting)
- [ ] 3. User/Profile (profile, vehicles, payout tokens, notification preferences; consumes AccountRegistered)
- [ ] 4. Listing (CRUD, presigned photos, availability rules/blackouts, PostGIS, moderation log, publish rules, outbox)
- [ ] 5. Booking (state machine, exclusion constraint, idempotency, policy + price snapshots, Listing REST call, saga)
- [ ] 6. Payment (PaymentProcessor + FakeProcessor, intents, ledger, commission_rules, payouts job, refunds)
- [ ] 7. Search (event consumers, geo + filter + availability query, cursor pagination, lastUpdatedFromEventAt)
- [ ] 8. Notification (event consumers, SES-compatible email interface, in-app feed, templates, retries + DLQ)
- [ ] 9. Review (eligibility projection, both directions, aggregates → events → Search)
- [ ] 10. Admin BFF (moderation queue, suspend account, disputes, payout hold)
- [ ] 11. Hardening (security pass, booking load test, Micrometer + OpenTelemetry, CI pipeline)
- [ ] 12. AWS (Terraform + deployment) — only on explicit go

## Environment notes
- Machine has JDK 17 on PATH and JDK 22 at `C:\Program Files\Java\jdk-22`. Build with `JAVA_HOME` pointing at JDK 22 (compiles with `--release 21`) or install JDK 21. The Maven enforcer rejects JDK < 21.
- Docker Desktop must be running for docker-compose and Testcontainers.
- `git config core.hooksPath .githooks` must be set once per clone (enables the attribution-stripping hook).

## Decisions log
- 2026-11-07 — Docs moved from `documentation/` to `docs/` and renamed to the names the build kit expects.
- 2026-11-07 — `ParkSmart-Microservices-Design.md` is not in the repo. Services-and-Entities says it consolidates that doc, so Services-and-Entities is authoritative and Architecture-Review is secondary.
- 2026-11-07 — Conflicts resolved in favour of Services-and-Entities:
  - Profile/vehicles have **no physical FK** to `accounts` (Architecture-Review §6 had `REFERENCES accounts(id)` across services).
  - `NotificationPreference` lives in **Profile Service**, not Notification Service (Architecture-Review §3.9 put `PUT /notification-preferences` on Notification). The endpoint moves to Profile.
  - Primary vehicle is `vehicles.is_primary`; no `profiles.default_vehicle_id` (Architecture-Review §3.2).
  - Exactly ten services; the "Availability" box in Architecture-Review §8 diagram is not a separate service (availability rules belong to Listing, the overlap invariant to Booking).
  - `vehicles.plate_region` + unique `(license_plate, plate_region)`; listing status includes `REJECTED`; `bookings.price_per_hour_snapshot` present.
- 2026-11-07 — Booking DB gets `btree_gist` (required for `listing_id WITH =` in the exclusion constraint; not stated in the docs).
- 2026-11-07 — Spring Boot **3.5.16** + Spring Cloud **2025.0.3** (latest 3.x line, per the fixed stack). Note: Boot 4.1 is current and 3.5 is past open-source support; revisit before production.
- 2026-11-07 — Local S3 stand-in is **RustFS** (`rustfs/rustfs:1.0.4`) instead of MinIO, because MinIO no longer publishes container images. Behind the photo-storage interface, so swappable.
- 2026-11-07 — One Postgres container with one database + one login role per service; each role can only connect to its own DB.
- 2026-11-07 — Spotless (google-java-format, LF line endings) runs `check` in `verify`; a PostToolUse hook auto-formats Java files Claude edits.

## Open questions (batch-ask at the start of Phase 1)
1. **Amenities**: Search document and filters use `amenities`, but `parking_listings` in the authoritative schema has no amenities column. Add `listing_amenities` to Listing?
2. **Disputes**: `Dispute` appears in the domain model and Admin BFF, but no service owns a table. Which service owns disputes (Booking or Payment)?
3. **Instant book vs request-to-book**: MVP instant-book only?
4. **Payment timeout**: PENDING_PAYMENT → EXPIRED after 10 minutes (from the saga diagram)?
5. **Cancellation policy shape**: fixed tiers (flexible/moderate/strict) snapshotted as JSONB?
6. **Buffer time** between bookings: per listing, or none in MVP?
7. **Currency**: INR only for MVP?
8. **Host eligibility**: require verified phone + profile photo before a listing can be published (Architecture-Review §3.2)?
