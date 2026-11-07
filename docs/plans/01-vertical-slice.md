# Plan 01 — Thin vertical slice

Goal: one user journey works end to end on local infrastructure, through the gateway, with real
events on Kafka:

**register → verify email → become HOST → (profile photo + verified phone) → create listing →
add photo + availability → submit → admin approve (PUBLISHED) → search → book → fake-pay →
(host approve if request-to-book) → CONFIRMED → check-in → check-out (COMPLETED) → review**

Contracts: [identity](../openapi/identity.yaml), [listing](../openapi/listing.yaml),
[booking](../openapi/booking.yaml), [payment](../openapi/payment.yaml),
[common](../openapi/common.yaml). Events: `shared/common-events` (`EventCatalog`, JSON Schemas in
`src/main/resources/event-schemas`).

## 1. Product decisions this plan relies on (2026-11-07)
| Topic | Decision |
|---|---|
| Booking mode | Host chooses per listing: `instantBook=true` → CONFIRMED on PaymentAuthorized; `false` → AWAITING_HOST_APPROVAL, host approves/declines, auto-expire at min(requestedAt + 24h, start) |
| Cancellation policy | FLEXIBLE / MODERATE / STRICT per listing (rules in `common.yaml#CancellationPolicy`), snapshotted on the booking. Host cancel/decline = 100% refund |
| Publish eligibility | HOST role + verified phone + profile photo, plus ≥ 1 READY photo and ≥ 1 availability rule. Admin approval moves PENDING_REVIEW → PUBLISHED |
| Buffer | Per-listing `bufferMinutes` (0–120, step 5), snapshotted; enforced by the DB on `blocked_range = [start, end + buffer)` |
| Payment timeout | PENDING_PAYMENT expires after 10 minutes |
| Capture timing | Capture when the booking becomes CONFIRMED (auth holds can lapse for far-future bookings; revisit with a real processor) |
| Currency | INR only |
| Time | Bookings on 15-minute boundaries; 30 min – 30 days; check-in window start − 15 min … start + 30 min; NO_SHOW after start + 30 min |

## 2. Slice walkthrough (exact calls and events)
All public calls go through the gateway at `http://localhost:8080` with `Authorization: Bearer`.

| # | Actor | Call | Effect / events |
|---|---|---|---|
| 1 | Host user | `POST /api/v1/auth/register` | Account PENDING_VERIFICATION, role DRIVER. `AccountRegistered` (→ Profile creates empty profile, → Notification). `VerificationCodeIssued{EMAIL_VERIFY}` on the delivery topic |
| 2 | Host | `POST /api/v1/auth/login` | Tokens |
| 3 | Host | `POST /api/v1/auth/email/verify {code}` | ACTIVE. `EmailVerified` |
| 4 | Host | `POST /api/v1/accounts/me/roles/host` then `POST /api/v1/auth/refresh` | `RoleGranted{HOST}`; new JWT carries HOST |
| 5 | Host | `POST /api/v1/auth/phone/verification-requests` → `POST /api/v1/auth/phone/verify` | `PhoneVerified` (→ Listing `host_eligibility.phone_verified`) |
| 6 | Host | Profile: `POST /api/v1/profiles/me/photo/upload-urls` → PUT to storage → confirm (contract in phase 3) | `ProfileUpdated{hasPhoto=true}` (→ Listing `host_eligibility.has_profile_photo`) |
| 7 | Host | `POST /api/v1/listings` | DRAFT. `ListingCreated` |
| 8 | Host | `POST /api/v1/listings/{id}/photos/upload-urls` → PUT → `.../confirm` | Photo READY |
| 9 | Host | `PUT /api/v1/listings/{id}/availability` | `AvailabilityChanged` only once published (drafts emit nothing to Search) |
| 10 | Host | `POST /api/v1/listings/{id}/submit` | PENDING_REVIEW. `ListingSubmittedForReview` (→ Notification to admins) |
| 11 | Admin | `GET /admin/v1/listings?status=PENDING_REVIEW` → `POST /admin/v1/listings/{id}/approve` | PUBLISHED. `ListingPublished{snapshot}` (→ Search indexes) |
| 12 | Driver | register / verify email (steps 1–3) | |
| 13 | Driver | `GET /api/v1/search/listings?lat&lng&radiusKm&startTime&endTime` (contract in phase 7) | Results from Search projection |
| 14 | Driver | `POST /api/v1/bookings` + `Idempotency-Key` | Booking calls `GET /internal/v1/listings/{id}/booking-quote` (sync), inserts PENDING_PAYMENT (exclusion constraint). 202. `BookingRequested` |
| 15 | Payment | consumes `BookingRequested` | PaymentIntent CREATED |
| 16 | Driver | `GET /api/v1/payments/intents?bookingId=` (poll until 200) → `POST /api/v1/payments/intents/{id}/authorize {paymentMethodToken: fake_ok}` | AUTHORIZED. `PaymentAuthorized` |
| 17 | Booking | consumes `PaymentAuthorized` | instantBook: CONFIRMED + `BookingConfirmed`. Else AWAITING_HOST_APPROVAL + `BookingAwaitingHostApproval` |
| 17b | Host | `POST /api/v1/bookings/{id}/approve` | CONFIRMED + `BookingConfirmed` |
| 18 | Payment | consumes `BookingConfirmed` | CAPTURED, ledger entries (driver DEBIT, host CREDIT net, platform CREDIT commission). `PaymentCaptured` |
| 18b | Listing, Search | consume `BookingConfirmed` | Listing: `listing_active_bookings` row (blocks archive). Search: availability cache |
| 19 | Driver | `POST /api/v1/bookings/{id}/check-in` → `.../check-out` | CHECKED_IN (`BookingCheckedIn`) → COMPLETED (`BookingCompleted`) |
| 20 | Review | consumes `BookingCompleted` | `review_eligibility` row (14-day window) |
| 21 | Driver | `POST /api/v1/reviews` (contract in phase 9) | `ReviewSubmitted`, `RatingAggregateUpdated` (→ Search, Profile) |

Failure paths that must also work in the slice tests: `fake_decline` → `PaymentFailed` → EXPIRED
(slot freed); no payment in 10 min → EXPIRED → Payment voids if it later authorizes; overlapping
request → 409 `booking-overlap`; host decline → DECLINED_BY_HOST → `PaymentVoided`; driver cancel
after capture → `BookingCancelled{refundAmount}` → `PaymentRefunded`.

### Saga races and how they resolve
- **Authorize vs. expiry**: Payment voids an intent whenever it sees `BookingExpired`/`BookingDeclined`/`BookingCancelled`, in either order. If `authorize` arrives after the void, it returns 409. If `PaymentAuthorized` reaches Booking after expiry, Booking ignores it (the matching expiry event already makes Payment void).
- **Duplicate events**: every consumer records `eventId` in `processed_events` inside the same transaction as its state change.
- **Lost publish**: all events go through the transactional outbox; a relay publishes rows and marks them sent (at-least-once).

## 3. Schema changes relative to Services-and-Entities §5
These are additions needed by the decisions above. Each is a decisions-log entry in PROGRESS.md.

**Identity**
- `accounts`: add `email_verified_at`, `phone_verified_at`. Phone uniqueness only among verified phones.
- `verification_tokens`: add `attempts INT` (lock after 5), `destination VARCHAR(255)` (phone being verified).
- `outbox_events` table (shared shape below).

**Listing**
- `parking_listings`: add `time_zone VARCHAR(64) NOT NULL`, `instant_book BOOLEAN NOT NULL`, `cancellation_policy VARCHAR(20) NOT NULL`, `buffer_minutes SMALLINT NOT NULL DEFAULT 0 CHECK (buffer_minutes BETWEEN 0 AND 120)`; `max_vehicle_type NOT NULL`.
- `listing_amenities (listing_id, amenity)` PK both columns.
- `listing_photos`: add `object_key`, `content_type`, `content_length`, `status (PENDING_UPLOAD|READY)`, `created_at`.
- `availability_rules.day_of_week` stored as ISO 1–7; `end_time` allows 24:00 (stored as `end_minute SMALLINT 1..1440` instead of TIME).
- Projections: `host_eligibility (host_account_id PK, phone_verified, has_profile_photo, updated_at)`; `listing_active_bookings (booking_id PK, listing_id, blocked_until)`.
- `outbox_events`, `processed_events`.

**Booking**
- `bookings`: add `host_account_id UUID NOT NULL` (for host authorization without calling Listing), `instant_book`, `buffer_minutes`, `blocked_range TSTZRANGE NOT NULL` (= `[lower(time_range), upper(time_range) + buffer)`, enforced by CHECK), `payment_due_by`, `host_decision_due_by`, `refund_amount`, `cancellation_policy VARCHAR(20)` (replaces JSONB snapshot; the policy table is fixed in code and versioned by name).
- Exclusion constraint moves to `blocked_range`; active statuses include `AWAITING_HOST_APPROVAL`. Requires `btree_gist`.
- `idempotency_key` unique **per driver**: `UNIQUE (driver_account_id, idempotency_key)` plus `request_hash` to detect a reused key with a different body.
- `outbox_events`, `processed_events`.

**Payment**
- `payment_intents`: add `driver_account_id`, `host_account_id`, `refunded_amount`, `failure_code`, `updated_at`, `version`; status adds `VOIDED`; `UNIQUE (booking_id)` (one intent per booking).
- `ledger_entries`: add `account_type (DRIVER|HOST|PLATFORM)`, `payout_id` (null until paid out).
- `commission_rules`: seed 15% effective from 2026-01-01.
- `idempotency_keys (key, scope, response_hash, created_at)` for authorize/refund.
- `outbox_events`, `processed_events`.

**Shared table shapes (per service DB)**
```sql
CREATE TABLE outbox_events (
  id UUID PRIMARY KEY,                -- = envelope eventId
  topic VARCHAR(100) NOT NULL,
  partition_key VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,             -- full envelope JSON
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE processed_events (
  event_id UUID NOT NULL,
  consumer VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, consumer)
);
```
The outbox relay is plain code in each service (scheduled poll with `FOR UPDATE SKIP LOCKED`,
batch publish, mark sent). It is not a shared library, because shared code is limited to the
three shared modules. A short helper may live in `common-events` later only if it has no Spring or
JPA dependency.

## 4. Cross-service calls (sync)
| Caller | Callee | Call | Timeout / failure |
|---|---|---|---|
| Booking | Listing | `GET /internal/v1/listings/{id}/booking-quote` | 2 s; 1 retry on connect error; Listing down → 503 `listing-unavailable` |
| Gateway | Identity | JWKS fetch (cached) | Gateway and services cache keys; refresh on unknown `kid` |

Everything else is events. `/internal/**` is never routed by the gateway. Internal calls forward
the caller's JWT (service-to-service credentials come in phase 11 hardening).

## 5. Build order (phases 2–9)
1. **Phase 2 — Gateway + Identity.** JWKS, login/refresh rotation, verification codes, outbox, `AccountRegistered`/`EmailVerified`/`PhoneVerified`/`RoleGranted`. Gateway: routes, JWT check, Redis rate limits, correlation ID, blocks `/internal/**`. Local profile logs verification codes (Notification arrives in phase 8). The `local` profile's code logger is disabled in `aws`.
2. **Phase 3 — Profile.** Consumes `AccountRegistered`; profile photo presigned upload; vehicles; `ProfileUpdated`.
3. **Phase 4 — Listing.** Everything in listing.yaml + `host_eligibility` projection (consumes `PhoneVerified`, `ProfileUpdated`) + `listing_active_bookings` (consumes booking events).
4. **Phase 5 — Booking** with a test stub for Payment events (`PaymentAuthorized`/`PaymentFailed` produced from tests).
5. **Phase 6 — Payment** with FakeProcessor; end-to-end saga test across Booking + Payment on Testcontainers Kafka.
6. **Phase 7 — Search** (PostGIS-backed first, behind `ListingSearchIndex` interface).
7. **Phase 8 — Notification** (replaces the local code logger as the delivery path).
8. **Phase 9 — Review.**

Parallelizable once phase 2 is merged: Profile, Listing, Notification (separate worktrees).
Sequential: Booking → Payment (shared saga).

## 6. Slice acceptance test
`docs/plans/01-vertical-slice.http` (added in phase 6) plus a scripted `scripts/slice-e2e.sh`
run against docker-compose + all services: walks steps 1–21, asserts each status, and checks the
ledger sums to zero for the booking (`DEBIT driver = CREDIT host + CREDIT platform`).
