-- Search Service: a projection of published listings, built only from events.
-- (Services-and-Entities `ListingSearchDocument`, including lastUpdatedFromEventAt.)

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE search_listings (
  listing_id UUID PRIMARY KEY,
  host_account_id UUID NOT NULL,
  title VARCHAR(255) NOT NULL,
  address VARCHAR(512) NOT NULL,
  latitude DOUBLE PRECISION NOT NULL,
  longitude DOUBLE PRECISION NOT NULL,
  location GEOGRAPHY(POINT, 4326)
    GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography) STORED,
  time_zone VARCHAR(64) NOT NULL,
  price_per_hour NUMERIC(8, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  max_vehicle_type VARCHAR(20) NOT NULL,
  max_vehicle_rank SMALLINT NOT NULL,           -- MOTORCYCLE 0 .. TRUCK 5
  amenities TEXT[] NOT NULL DEFAULT '{}',
  instant_book BOOLEAN NOT NULL,
  cancellation_policy VARCHAR(20) NOT NULL,
  buffer_minutes SMALLINT NOT NULL,
  thumbnail_url VARCHAR(1024),
  visible BOOLEAN NOT NULL,                     -- PUBLISHED
  rating_avg NUMERIC(3, 2),
  rating_count INT NOT NULL DEFAULT 0,
  rating_updated_at TIMESTAMPTZ,
  source_updated_at TIMESTAMPTZ NOT NULL,       -- event time of the last applied listing change
  availability_updated_at TIMESTAMPTZ NOT NULL,
  last_event_at TIMESTAMPTZ NOT NULL            -- when Search last applied any event for it
);
CREATE INDEX idx_search_geo ON search_listings USING GIST (location) WHERE visible;
CREATE INDEX idx_search_amenities ON search_listings USING GIN (amenities) WHERE visible;

CREATE TABLE search_rules (
  listing_id UUID NOT NULL REFERENCES search_listings (listing_id) ON DELETE CASCADE,
  day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
  start_minute SMALLINT NOT NULL,
  end_minute SMALLINT NOT NULL
);
CREATE INDEX idx_search_rules ON search_rules (listing_id);

CREATE TABLE search_blackouts (
  listing_id UUID NOT NULL REFERENCES search_listings (listing_id) ON DELETE CASCADE,
  start_at TIMESTAMPTZ NOT NULL,
  end_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_search_blackouts ON search_blackouts (listing_id, end_at);

-- Known bookings occupying listing time. Ended ones stay as tombstones (purged after 30 days).
CREATE TABLE search_busy (
  booking_id UUID PRIMARY KEY,
  listing_id UUID,
  start_at TIMESTAMPTZ,
  blocked_until TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_search_busy ON search_busy (listing_id, blocked_until) WHERE ended_at IS NULL;

CREATE TABLE processed_events (
  event_id UUID NOT NULL,
  consumer VARCHAR(100) NOT NULL,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, consumer)
);
CREATE INDEX idx_processed_events_age ON processed_events (processed_at);
