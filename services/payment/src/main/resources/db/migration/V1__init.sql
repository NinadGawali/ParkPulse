-- Payment Service schema (docs/ParkSmart-Services-and-Entities.md section 5, PAYMENT, plus
-- docs/plans/01-vertical-slice.md section 3). Booking, driver and host IDs are logical references.
-- Deviations from the doc: intent carries driver/host IDs, payment_due_by, refunded_amount,
-- failure_code, updated_at, version and status VOIDED, one intent per booking; ledger entries
-- carry account_type, currency, booking_id and payout_id; idempotency_keys table; payouts carry
-- currency and failure_reason.

CREATE TABLE payment_intents (
  id UUID PRIMARY KEY,
  booking_id UUID NOT NULL,
  driver_account_id UUID NOT NULL,
  host_account_id UUID NOT NULL,
  amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL CHECK (currency = 'INR'),
  status VARCHAR(20) NOT NULL,
  processor_ref VARCHAR(255),
  payment_due_by TIMESTAMPTZ NOT NULL,
  refunded_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
  -- Commission taken at capture (rate effective then); refunds reverse it pro rata.
  commission_amount NUMERIC(10, 2) CHECK (commission_amount >= 0 AND commission_amount <= amount),
  failure_code VARCHAR(50),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT uq_intent_booking UNIQUE (booking_id),
  CONSTRAINT chk_intent_status CHECK (status IN
    ('CREATED', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'VOIDED', 'REFUNDED', 'PARTIALLY_REFUNDED')),
  CONSTRAINT chk_refunded CHECK (refunded_amount >= 0 AND refunded_amount <= amount)
);
CREATE INDEX idx_intents_driver ON payment_intents (driver_account_id);

-- Idempotency for client calls (authorize, admin refund): same key + same body = same result.
CREATE TABLE idempotency_keys (
  scope VARCHAR(30) NOT NULL,
  actor_id UUID NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_hash VARCHAR(64) NOT NULL,
  intent_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (scope, actor_id, idempotency_key)
);

CREATE TABLE commission_rules (
  id UUID PRIMARY KEY,
  rate_percent NUMERIC(5, 2) NOT NULL CHECK (rate_percent >= 0 AND rate_percent < 100),
  effective_from TIMESTAMPTZ NOT NULL,
  effective_to TIMESTAMPTZ,
  CHECK (effective_to IS NULL OR effective_to > effective_from)
);
INSERT INTO commission_rules (id, rate_percent, effective_from)
VALUES ('00000000-0000-0000-0000-000000000015', 15.00, '2026-01-01T00:00:00Z');

CREATE TABLE payouts (
  id UUID PRIMARY KEY,
  host_account_id UUID NOT NULL,
  amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
  currency CHAR(3) NOT NULL DEFAULT 'INR',
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'ON_HOLD')),
  processor_payout_ref VARCHAR(255),
  failure_reason VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payouts_host ON payouts (host_account_id, created_at DESC, id DESC);

-- Immutable double-entry ledger. For every reference the DEBITs equal the CREDITs.
-- The platform's own account uses account_id 00000000-0000-0000-0000-000000000000.
CREATE TABLE ledger_entries (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL,
  account_type VARCHAR(10) NOT NULL CHECK (account_type IN ('DRIVER', 'HOST', 'PLATFORM')),
  entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
  amount NUMERIC(10, 2) NOT NULL CHECK (amount >= 0),
  currency CHAR(3) NOT NULL DEFAULT 'INR',
  reference_type VARCHAR(30) NOT NULL CHECK (reference_type IN ('CAPTURE', 'REFUND')),
  reference_id UUID NOT NULL,
  intent_id UUID NOT NULL REFERENCES payment_intents (id),
  booking_id UUID NOT NULL,
  payout_id UUID REFERENCES payouts (id),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ledger_account_time ON ledger_entries (account_id, created_at);
CREATE INDEX idx_ledger_unpaid_host ON ledger_entries (account_id, created_at)
  WHERE account_type = 'HOST' AND payout_id IS NULL;
CREATE INDEX idx_ledger_intent ON ledger_entries (intent_id);

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
