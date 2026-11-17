package com.parksmart.booking.application.port;

import com.parksmart.booking.domain.Booking;
import com.parksmart.booking.domain.BookingStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository {

  Optional<Booking> findById(UUID id);

  Optional<Booking> findByIdempotencyKey(UUID driverAccountId, String idempotencyKey);

  /**
   * Inserts a new booking. Overlap with another active booking on the listing throws {@code
   * DomainException("booking-overlap")} (exclusion constraint); a concurrent request with the same
   * idempotency key throws {@link DuplicateIdempotencyKey}.
   */
  void insert(Booking booking);

  /**
   * Applies a transition the domain already validated: {@code UPDATE ... WHERE id = ? AND status =
   * from AND version = booking.version()}, plus a status history row.
   *
   * @return false if another actor changed the booking first (nothing was written)
   */
  boolean transition(Booking booking, BookingStatus from, UUID changedBy, String reason);

  enum Role {
    DRIVER,
    HOST
  }

  enum When {
    UPCOMING,
    PAST,
    ALL
  }

  /**
   * Bookings of an account as driver or host. Upcoming: end after now, ascending by start. Past and
   * all: descending by start. Keyset after {@code (afterStart, afterId)}.
   */
  List<Booking> page(
      Role role,
      UUID accountId,
      When when,
      BookingStatus status,
      Instant now,
      Instant afterStart,
      UUID afterId,
      int limit);

  /** Locked (SKIP LOCKED) batches for the timer jobs. */
  List<Booking> lockDue(Timer timer, Instant now, int limit);

  enum Timer {
    PAYMENT_EXPIRY,
    HOST_DECISION_EXPIRY,
    NO_SHOW,
    COMPLETION
  }

  /** Unpaid (PENDING_PAYMENT) bookings a driver holds right now. */
  int countPending(UUID driverAccountId);

  /** Records that a listing was archived (idempotent). */
  void markListingArchived(UUID listingId, Instant at);

  boolean isListingArchived(UUID listingId);

  /** Active bookings of a listing (for a withdrawn listing). */
  List<Booking> activeByListing(UUID listingId);

  /** Two requests with the same driver + idempotency key raced; the other one won. */
  final class DuplicateIdempotencyKey extends RuntimeException {
    public DuplicateIdempotencyKey() {
      super("duplicate idempotency key", null, false, false);
    }
  }
}
