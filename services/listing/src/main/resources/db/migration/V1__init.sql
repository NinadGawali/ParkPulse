-- Listing Service schema (docs/ParkSmart-Services-and-Entities.md section 5, LISTING, plus the
-- additions in docs/plans/01-vertical-slice.md section 3). Host and booking IDs are logical
-- references to other services; there are no cross-service foreign keys.
-- Deviations from the doc:
--   * latitude/longitude columns; `location` is a generated GEOGRAPHY column (keeps the GiST index
--     without mapping PostGIS types in JPA)
--   * availability_blackouts stores start_at/end_at; `date_range` is a generated TSTZRANGE
--   * availability_rules use ISO day 1-7 and minutes (end_minute up to 1440 = 24:00)
--   * listing_photos carry upload state (staging key, PENDING_UPLOAD/READY)
--   * projections host_eligibility and listing_active_bookings

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE parking_listings (
  id UUID PRIMARY KEY,
  host_account_id UUID NOT NULL,
  title VARCHAR(255) NOT NULL,
  description TEXT,
  address VARCHAR(512) NOT NULL,
  latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  location GEOGRAPHY(POINT, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED,
  time_zone VARCHAR(64) NOT NULL,
  price_per_hour NUMERIC(8, 2) NOT NULL CHECK (price_per_hour > 0),
  currency CHAR(3) NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
  max_vehicle_type VARCHAR(20) NOT NULL
    CHECK (max_vehicle_type IN ('MOTORCYCLE', 'HATCHBACK', 'SEDAN', 'SUV', 'VAN', 'TRUCK')),
  instant_book BOOLEAN NOT NULL,
  cancellation_policy VARCHAR(20) NOT NULL
    CHECK (cancellation_policy IN ('FLEXIBLE', 'MODERATE', 'STRICT')),
  buffer_minutes SMALLINT NOT NULL DEFAULT 0
    CHECK (buffer_minutes BETWEEN 0 AND 120 AND buffer_minutes % 5 = 0),
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  deleted_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_listing_status CHECK (status IN
    ('DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'PAUSED', 'ARCHIVED', 'REJECTED')),
  CONSTRAINT chk_archived_deleted CHECK ((status = 'ARCHIVED') = (deleted_at IS NOT NULL))
);
CREATE INDEX idx_listings_geo ON parking_listings USING GIST (location);
CREATE INDEX idx_listings_host ON parking_listings (host_account_id, created_at DESC, id DESC)
  WHERE deleted_at IS NULL;
CREATE INDEX idx_listings_status ON parking_listings (status, updated_at, id)
  WHERE deleted_at IS NULL;

CREATE TABLE listing_amenities (
  listing_id UUID NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
  amenity VARCHAR(30) NOT NULL CHECK (amenity IN ('COVERED', 'GATED', 'CCTV', 'EV_CHARGER',
    'SECURITY_GUARD', 'LIGHTING', 'WHEELCHAIR_ACCESSIBLE', 'TWENTY_FOUR_SEVEN_ACCESS')),
  PRIMARY KEY (listing_id, amenity)
);

CREATE TABLE listing_photos (
  id UUID PRIMARY KEY,
  listing_id UUID NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING_UPLOAD', 'READY')),
  object_key VARCHAR(255),
  url VARCHAR(1024),
  staging_key VARCHAR(255),
  content_type VARCHAR(30) NOT NULL,
  content_length BIGINT NOT NULL CHECK (content_length > 0),
  sort_order INT NOT NULL DEFAULT 0,
  upload_expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_ready_photo_has_url
    CHECK ((status = 'READY') = (url IS NOT NULL AND object_key IS NOT NULL))
);
CREATE INDEX idx_listing_photos_listing ON listing_photos (listing_id, sort_order);
CREATE INDEX idx_listing_photos_staging ON listing_photos (upload_expires_at)
  WHERE staging_key IS NOT NULL;

CREATE TABLE availability_rules (
  id UUID PRIMARY KEY,
  listing_id UUID NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
  day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
  start_minute SMALLINT NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute SMALLINT NOT NULL CHECK (end_minute BETWEEN 1 AND 1440),
  CHECK (start_minute < end_minute)
);
CREATE INDEX idx_availability_rules_listing ON availability_rules (listing_id, day_of_week);

CREATE TABLE availability_blackouts (
  id UUID PRIMARY KEY,
  listing_id UUID NOT NULL REFERENCES parking_listings (id) ON DELETE CASCADE,
  start_at TIMESTAMPTZ NOT NULL,
  end_at TIMESTAMPTZ NOT NULL,
  date_range TSTZRANGE GENERATED ALWAYS AS (tstzrange(start_at, end_at, '[)')) STORED,
  reason VARCHAR(255),
  CHECK (start_at < end_at)
);
CREATE INDEX idx_blackouts_listing ON availability_blackouts (listing_id, end_at);

CREATE TABLE listing_moderation_log (
  id UUID PRIMARY KEY,
  listing_id UUID NOT NULL REFERENCES parking_listings (id),
  reviewed_by UUID NOT NULL,
  decision VARCHAR(20) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
  reason VARCHAR(500),
  reviewed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_moderation_log_listing ON listing_moderation_log (listing_id, reviewed_at DESC);

-- Projection of Identity/Profile events: may this host publish? Each source field carries the
-- time of the event that set it, so out-of-order events never roll it back.
CREATE TABLE host_eligibility (
  host_account_id UUID PRIMARY KEY,
  phone_verified BOOLEAN NOT NULL DEFAULT false,
  phone_changed_at TIMESTAMPTZ,
  has_profile_photo BOOLEAN NOT NULL DEFAULT false,
  photo_changed_at TIMESTAMPTZ,
  suspended BOOLEAN NOT NULL DEFAULT false,
  suspended_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL
);

-- Projection of Booking events: bookings that still block the listing (archive check).
-- Ended rows are kept (ended_at set) so a late or duplicate "active" event cannot revive them.
CREATE TABLE listing_active_bookings (
  booking_id UUID PRIMARY KEY,
  listing_id UUID,
  blocked_until TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_active_bookings_listing ON listing_active_bookings (listing_id, blocked_until)
  WHERE ended_at IS NULL;

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
