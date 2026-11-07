-- Review fixes: refund audit trail with deterministic IDs, payout uniqueness per period.
CREATE TABLE refunds (
  id UUID PRIMARY KEY,                -- deterministic: also the processor idempotency key
  intent_id UUID NOT NULL REFERENCES payment_intents (id),
  amount NUMERIC(10, 2) NOT NULL CHECK (amount > 0),
  source VARCHAR(20) NOT NULL CHECK (source IN ('CANCELLATION', 'ADMIN')),
  actor_id UUID,
  reason VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_refunds_intent ON refunds (intent_id, created_at);

-- At most one live (not FAILED) payout per host and period.
CREATE UNIQUE INDEX uq_payout_host_period ON payouts (host_account_id, period_end)
  WHERE status <> 'FAILED';
-- One capture per intent (defence in depth on top of the status check).
CREATE UNIQUE INDEX uq_ledger_capture ON ledger_entries (intent_id, account_type)
  WHERE reference_type = 'CAPTURE';
