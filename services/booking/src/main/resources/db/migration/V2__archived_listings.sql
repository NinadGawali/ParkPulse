-- Booking-local projection of archived listings (from ListingArchived). Create and the
-- PaymentAuthorized handler check it, so a booking that raced an archive is ended.
CREATE TABLE archived_listings (
  listing_id UUID PRIMARY KEY,
  archived_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_bookings_driver_pending ON bookings (driver_account_id)
  WHERE status = 'PENDING_PAYMENT';
