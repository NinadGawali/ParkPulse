-- Booking Service schema (docs/ParkSmart-Services-and-Entities.md section 5, BOOKING, plus
-- docs/plans/01-vertical-slice.md section 3). Driver, host, listing and vehicle IDs are logical
-- references to other services; there are no cross-service foreign keys.
-- Deviations from the doc:
--   * start_time/end_time plus blocked_until (= end + buffer) and blocked_range; the exclusion
--     constraint is on blocked_range, so the host's turnaround buffer is also protected
--   * active statuses include AWAITING_HOST_APPROVAL
--   * idempotency key is unique per driver, with a request hash to detect reuse
--   * cancellation_policy is a name (FLEXIBLE/MODERATE/STRICT) instead of a JSONB snapshot

-- Needed for "listing_id WITH =" inside a GiST exclusion constraint (trusted extension).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE bookings (
  id UUID PRIMARY KEY,
  driver_account_id UUID NOT NULL,
  host_account_id UUID NOT NULL,
  listing_id UUID NOT NULL,
  vehicle_id UUID,
  start_time TIMESTAMPTZ NOT NULL,
  end_time TIMESTAMPTZ NOT NULL,
  blocked_until TIMESTAMPTZ NOT NULL,
  blocked_range TSTZRANGE NOT NULL,
  status VARCHAR(30) NOT NULL,
  price_per_hour_snapshot NUMERIC(8, 2) NOT NULL CHECK (price_per_hour_snapshot > 0),
  total_amount NUMERIC(10, 2) NOT NULL CHECK (total_amount > 0),
  currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  instant_book BOOLEAN NOT NULL,
  cancellation_policy VARCHAR(20) NOT NULL
    CHECK (cancellation_policy IN ('FLEXIBLE', 'MODERATE', 'STRICT')),
  buffer_minutes SMALLINT NOT NULL CHECK (buffer_minutes BETWEEN 0 AND 120),
  payment_due_by TIMESTAMPTZ,
  host_decision_due_by TIMESTAMPTZ,
  refund_amount NUMERIC(10, 2) CHECK (refund_amount >= 0 AND refund_amount <= total_amount),
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT chk_booking_status CHECK (status IN ('PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL',
    'CONFIRMED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED_BY_DRIVER', 'CANCELLED_BY_HOST',
    'DECLINED_BY_HOST', 'NO_SHOW', 'EXPIRED', 'PAYMENT_FAILED')),
  CONSTRAINT chk_booking_times CHECK (start_time < end_time AND blocked_until >= end_time),
  CONSTRAINT chk_blocked_range CHECK (blocked_range = tstzrange(start_time, blocked_until, '[)')),
  CONSTRAINT uq_booking_idempotency UNIQUE (driver_account_id, idempotency_key),
  -- The invariant: two active bookings can never hold overlapping time on one listing.
  CONSTRAINT excl_booking_overlap EXCLUDE USING gist (
    listing_id WITH =,
    blocked_range WITH &&
  ) WHERE (status IN ('PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL', 'CONFIRMED', 'CHECKED_IN'))
);
CREATE INDEX idx_bookings_driver ON bookings (driver_account_id, start_time, id);
CREATE INDEX idx_bookings_host ON bookings (host_account_id, start_time, id);
CREATE INDEX idx_bookings_listing_active ON bookings (listing_id)
  WHERE status IN ('PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL', 'CONFIRMED', 'CHECKED_IN');
-- Scheduler queries (one per timed transition).
CREATE INDEX idx_bookings_payment_due ON bookings (payment_due_by) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_bookings_host_due ON bookings (host_decision_due_by)
  WHERE status = 'AWAITING_HOST_APPROVAL';
CREATE INDEX idx_bookings_confirmed_start ON bookings (start_time) WHERE status = 'CONFIRMED';
CREATE INDEX idx_bookings_checked_in_end ON bookings (end_time) WHERE status = 'CHECKED_IN';

CREATE TABLE booking_status_history (
  id UUID PRIMARY KEY,
  booking_id UUID NOT NULL REFERENCES bookings (id),
  from_status VARCHAR(30),
  to_status VARCHAR(30) NOT NULL,
  changed_by UUID,
  reason VARCHAR(255),
  changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_booking_history ON booking_status_history (booking_id, changed_at);

CREATE TABLE outbox_events (
  id UUID PRIMARY KEY,
  topic VARCHAR(100) NOT NULL,
  partition_key VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
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
CREATE INDEX idx_processed_events_age ON processed_events (processed_at);
