-- Review Service schema (docs/ParkSmart-Services-and-Entities.md section 5, REVIEW).
-- Deviations: review_eligibility carries listing_id and completed_at; reviews carry
-- moderation fields; rating_aggregates is keyed by (target_type, target_id).

CREATE TABLE review_eligibility (
  booking_id UUID PRIMARY KEY,
  driver_account_id UUID NOT NULL,
  host_account_id UUID NOT NULL,
  listing_id UUID NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE reviews (
  id UUID PRIMARY KEY,
  booking_id UUID NOT NULL,
  direction VARCHAR(20) NOT NULL CHECK (direction IN ('DRIVER_TO_LISTING', 'HOST_TO_DRIVER')),
  author_account_id UUID NOT NULL,
  target_type VARCHAR(10) NOT NULL CHECK (target_type IN ('LISTING', 'DRIVER')),
  target_id UUID NOT NULL,
  rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment VARCHAR(1000),
  moderation_status VARCHAR(10) NOT NULL DEFAULT 'PUBLISHED'
    CHECK (moderation_status IN ('PUBLISHED', 'HIDDEN')),
  moderated_by UUID,
  moderation_reason VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_review_per_direction UNIQUE (booking_id, direction)
);
CREATE INDEX idx_reviews_target ON reviews (target_type, target_id, created_at DESC, id DESC)
  WHERE moderation_status = 'PUBLISHED';

CREATE TABLE rating_aggregates (
  target_type VARCHAR(10) NOT NULL,
  target_id UUID NOT NULL,
  avg_rating NUMERIC(3, 2),
  review_count INT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (target_type, target_id)
);

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
