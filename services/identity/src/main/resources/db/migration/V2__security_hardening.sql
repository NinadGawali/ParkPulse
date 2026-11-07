-- Security review fixes (2026-11-07).

-- Absolute lifetime of a login: every rotation keeps the family's start time.
ALTER TABLE refresh_tokens ADD COLUMN family_started_at TIMESTAMPTZ;
UPDATE refresh_tokens SET family_started_at = issued_at WHERE family_started_at IS NULL;
ALTER TABLE refresh_tokens ALTER COLUMN family_started_at SET NOT NULL;

-- Resend cooldown / hourly cap look up recent tokens per account and purpose.
CREATE INDEX idx_verification_recent ON verification_tokens (account_id, purpose, created_at DESC);
