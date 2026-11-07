-- Identity Service schema. Source: docs/ParkSmart-Services-and-Entities.md §5 (Identity),
-- plus the deltas listed in docs/plans/01-vertical-slice.md §3.

CREATE TABLE accounts (
  id UUID PRIMARY KEY,
  email VARCHAR(255) NOT NULL,
  email_normalized VARCHAR(255) GENERATED ALWAYS AS (LOWER(email)) STORED,
  phone VARCHAR(20),                       -- verified phone only (E.164)
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
  email_verified_at TIMESTAMPTZ,
  phone_verified_at TIMESTAMPTZ,
  accepted_terms_version VARCHAR(20) NOT NULL,
  failed_login_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,
  version INT NOT NULL DEFAULT 0,
  CONSTRAINT uq_accounts_email UNIQUE (email_normalized),
  CONSTRAINT chk_accounts_status CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DELETED')),
  CONSTRAINT chk_accounts_phone_verified CHECK ((phone IS NULL) = (phone_verified_at IS NULL))
);
CREATE UNIQUE INDEX uq_accounts_phone ON accounts (phone) WHERE phone IS NOT NULL AND deleted_at IS NULL;

CREATE TABLE roles (
  account_id UUID NOT NULL REFERENCES accounts(id),
  role_type VARCHAR(20) NOT NULL,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, role_type),
  CONSTRAINT chk_roles_type CHECK (role_type IN ('DRIVER','HOST','ADMIN'))
);

CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id),
  family_id UUID NOT NULL,                 -- all rotations of one login; revoked together on reuse
  token_hash VARCHAR(255) NOT NULL,
  issued_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked BOOLEAN NOT NULL DEFAULT false,
  replaced_by_token_id UUID REFERENCES refresh_tokens(id),
  CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_account ON refresh_tokens (account_id) WHERE revoked = false;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE verification_tokens (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL REFERENCES accounts(id),
  purpose VARCHAR(20) NOT NULL,
  token_hash VARCHAR(255) NOT NULL,        -- HMAC of the code/token, never the value itself
  destination VARCHAR(255),                -- phone being verified (PHONE_VERIFY)
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  CONSTRAINT chk_verification_purpose CHECK (purpose IN ('EMAIL_VERIFY','PHONE_VERIFY','PASSWORD_RESET'))
);
CREATE INDEX idx_verification_active ON verification_tokens (account_id, purpose) WHERE consumed_at IS NULL;
CREATE INDEX idx_verification_hash ON verification_tokens (token_hash) WHERE consumed_at IS NULL;

-- Transactional outbox (docs/plans/01-vertical-slice.md §3).
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
