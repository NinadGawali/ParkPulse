-- Profile Service schema (docs/ParkSmart-Services-and-Entities.md section 5, USER / PROFILE).
-- Account IDs are logical references to Identity; there are no cross-service foreign keys.
-- Deviations from the doc: photo_object_key, cached driver rating, created_at/updated_at and
-- optimistic-lock version columns; profile_photo_uploads; vehicle_type and channel CHECKs.

CREATE TABLE profiles (
  account_id UUID PRIMARY KEY,
  display_name VARCHAR(150),
  bio TEXT,
  city VARCHAR(150),
  photo_url VARCHAR(1024),
  photo_object_key VARCHAR(255),
  rating_avg NUMERIC(3, 2) CHECK (rating_avg BETWEEN 1 AND 5),
  rating_count INT NOT NULL DEFAULT 0 CHECK (rating_count >= 0),
  rating_updated_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_profile_photo_pair CHECK ((photo_url IS NULL) = (photo_object_key IS NULL))
);

CREATE TABLE profile_photo_uploads (
  id UUID PRIMARY KEY,
  account_id UUID NOT NULL,
  object_key VARCHAR(255) NOT NULL UNIQUE,
  content_type VARCHAR(30) NOT NULL,
  content_length BIGINT NOT NULL CHECK (content_length > 0),
  expires_at TIMESTAMPTZ NOT NULL,
  confirmed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_photo_uploads_pending ON profile_photo_uploads (account_id, expires_at)
  WHERE confirmed_at IS NULL;
CREATE INDEX idx_photo_uploads_expiry ON profile_photo_uploads (expires_at)
  WHERE confirmed_at IS NULL;

CREATE TABLE vehicles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_account_id UUID NOT NULL,
  license_plate VARCHAR(20) NOT NULL,
  plate_region VARCHAR(10) NOT NULL,
  vehicle_type VARCHAR(20) NOT NULL
    CHECK (vehicle_type IN ('MOTORCYCLE', 'HATCHBACK', 'SEDAN', 'SUV', 'VAN', 'TRUCK')),
  make VARCHAR(60),
  model VARCHAR(60),
  color VARCHAR(30),
  is_primary BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  deleted_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_deleted_not_primary CHECK (deleted_at IS NULL OR NOT is_primary)
);
CREATE UNIQUE INDEX uq_one_primary_vehicle ON vehicles (owner_account_id)
  WHERE is_primary AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_plate_per_region ON vehicles (license_plate, plate_region)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_vehicles_owner ON vehicles (owner_account_id, created_at)
  WHERE deleted_at IS NULL;

CREATE TABLE payout_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  host_account_id UUID NOT NULL,
  processor_token VARCHAR(255) NOT NULL,
  processor_type VARCHAR(30) NOT NULL
    CHECK (processor_type IN ('STRIPE_CONNECT', 'RAZORPAY_ROUTE', 'FAKE')),
  verified BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_payout_processor_token UNIQUE (processor_type, processor_token)
);
CREATE INDEX idx_payout_accounts_host ON payout_accounts (host_account_id, created_at);

CREATE TABLE notification_preferences (
  account_id UUID NOT NULL,
  channel VARCHAR(20) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'IN_APP')),
  enabled BOOLEAN NOT NULL DEFAULT true,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (account_id, channel)
);

-- Transactional outbox (same shape as every service).
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

-- Idempotent consumers: one row per (event, consumer) handled.
CREATE TABLE processed_events (
  event_id UUID NOT NULL,
  consumer VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_events_age ON processed_events (processed_at);
