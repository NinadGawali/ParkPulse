-- Review fix: ratings live in their own table so a rating that arrives before the listing's
-- first snapshot is kept (an UPDATE on search_listings would have matched nothing).
CREATE TABLE search_ratings (
  listing_id UUID PRIMARY KEY,
  rating_avg NUMERIC(3, 2),
  rating_count INT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);
ALTER TABLE search_listings DROP COLUMN rating_avg, DROP COLUMN rating_count,
  DROP COLUMN rating_updated_at;
