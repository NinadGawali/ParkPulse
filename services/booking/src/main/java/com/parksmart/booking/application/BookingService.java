package com.parksmart.booking.application;

import com.parksmart.booking.application.port.BookingRepository;
import com.parksmart.booking.application.port.BookingRepository.DuplicateIdempotencyKey;
import com.parksmart.booking.application.port.BookingRepository.Role;
import com.parksmart.booking.application.port.BookingRepository.When;
import com.parksmart.booking.application.port.ListingQuotes;
import com.parksmart.booking.application.port.ListingQuotes.Quote;
import com.parksmart.booking.domain.Booking;
import com.parksmart.booking.domain.BookingStatus;
import com.parksmart.booking.domain.CancellationPolicy;
import com.parksmart.booking.domain.DomainException;
import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.Cursor;
import com.parksmart.common.web.CursorPage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Booking use cases for drivers and hosts. Every mutation checks that the caller is the right party
 * (403 otherwise) and applies the transition with a conditional update, so a race with another
 * actor or a timer ends in 409 {@code illegal-booking-transition} for the loser.
 */
@Service
public class BookingService {

  private final BookingRepository bookings;
  private final ListingQuotes listings;
  private final BookingEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public BookingService(
      BookingRepository bookings,
      ListingQuotes listings,
      BookingEvents events,
      TransactionTemplate tx,
      Clock clock) {
    this.bookings = bookings;
    this.listings = listings;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  public record CreateCommand(UUID listingId, UUID vehicleId, Instant start, Instant end) {}

  /** {@code replay} is true when the same request was made before with this key. */
  public record Created(Booking booking, boolean replay) {}

  /**
   * Quotes the listing (outside any transaction: it is a remote call), then holds the slot. The
   * exclusion constraint, not this code, guarantees no double booking.
   */
  public Created create(AuthenticatedAccount driver, String idempotencyKey, CreateCommand cmd) {
    String hash = requestHash(cmd);
    var earlier = bookings.findByIdempotencyKey(driver.accountId(), idempotencyKey);
    if (earlier.isPresent()) {
      return replay(earlier.get(), hash);
    }
    Instant now = clock.instant();
    Booking.validateWindow(cmd.start(), cmd.end(), now);
    Quote quote = listings.quote(cmd.listingId(), cmd.start(), cmd.end(), driver.accessToken());
    if (driver.accountId().equals(quote.hostAccountId())) {
      throw new DomainException("cannot-book-own-listing", "You cannot book your own listing");
    }
    if (!quote.bookable()) {
      throw new DomainException(
          "listing-not-bookable",
          "The listing cannot be booked for this window (" + quote.unbookableReason() + ")");
    }
    Booking booking =
        Booking.request(
            UUID.randomUUID(),
            driver.accountId(),
            cmd.listingId(),
            cmd.vehicleId(),
            cmd.start(),
            cmd.end(),
            new Booking.Terms(
                quote.hostAccountId(),
                quote.pricePerHour(),
                quote.currency(),
                quote.instantBook(),
                CancellationPolicy.valueOf(quote.cancellationPolicy()),
                quote.bufferMinutes()),
            idempotencyKey,
            hash,
            clock.instant());
    try {
      tx.executeWithoutResult(status -> {
        bookings.insert(booking);
        events.requested(booking);
      });
      return new Created(booking, false);
    } catch (DuplicateIdempotencyKey e) {
      Booking winner =
          bookings.findByIdempotencyKey(driver.accountId(), idempotencyKey).orElseThrow();
      return replay(winner, hash);
    }
  }

  private static Created replay(Booking earlier, String hash) {
    if (!earlier.requestHash().equals(hash)) {
      throw new DomainException(
          "idempotency-key-reused", "This Idempotency-Key was used for a different booking");
    }
    return new Created(earlier, true);
  }

  static String requestHash(CreateCommand cmd) {
    String canonical =
        cmd.listingId()
            + "|"
            + Objects.toString(cmd.vehicleId(), "")
            + "|"
            + cmd.start()
            + "|"
            + cmd.end();
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The driver, the host, or an admin; everyone else gets 404 (bookings are private). */
  @Transactional(readOnly = true)
  public Booking get(AuthenticatedAccount caller, UUID bookingId) {
    Booking b = bookings.findById(bookingId).orElseThrow(BookingService::notFound);
    if (!b.isDriver(caller.accountId()) && !b.isHost(caller.accountId()) && !caller.isAdmin()) {
      throw notFound();
    }
    return b;
  }

  @Transactional(readOnly = true)
  public CursorPage<Booking> page(
      UUID accountId, Role role, When when, BookingStatus status, String cursor, Integer limit) {
    Cursor after = Cursor.decode(cursor);
    int size = Cursor.limit(limit);
    List<Booking> rows =
        bookings.page(
            role,
            accountId,
            when,
            status,
            clock.instant(),
            after == null ? null : after.sortKey(),
            after == null ? null : after.id(),
            size + 1);
    if (rows.size() <= size) {
      return new CursorPage<>(rows, null);
    }
    List<Booking> items = rows.subList(0, size);
    Booking last = items.get(size - 1);
    return new CursorPage<>(items, new Cursor(last.startTime(), last.id()).encode());
  }

  // ---------------------------------------------------------------- party actions

  @Transactional
  public Booking cancel(UUID callerId, UUID bookingId, String reason) {
    Booking b = load(bookingId);
    if (b.isDriver(callerId)) {
      return apply(b, x -> x.cancelByDriver(clock.instant()), callerId, reason);
    }
    if (b.isHost(callerId)) {
      return apply(b, x -> x.cancelByHost(clock.instant()), callerId, reason);
    }
    throw notParty();
  }

  @Transactional
  public Booking approve(UUID callerId, UUID bookingId) {
    Booking b = asHost(callerId, bookingId);
    return apply(b, x -> x.approve(clock.instant()), callerId, "approved by host");
  }

  @Transactional
  public Booking decline(UUID callerId, UUID bookingId, String reason) {
    Booking b = asHost(callerId, bookingId);
    return apply(b, x -> x.decline(clock.instant()), callerId, reason);
  }

  @Transactional
  public Booking checkIn(UUID callerId, UUID bookingId) {
    Booking b = asDriver(callerId, bookingId);
    return apply(b, x -> x.checkIn(clock.instant()), callerId, null);
  }

  @Transactional
  public Booking checkOut(UUID callerId, UUID bookingId) {
    Booking b = asDriver(callerId, bookingId);
    return apply(b, x -> x.checkOut(clock.instant()), callerId, null);
  }

  /** Domain-validated transition, conditional write, event. A lost race is a 409. */
  private Booking apply(
      Booking b, Function<Booking, BookingStatus> step, UUID actor, String reason) {
    BookingStatus from = step.apply(b);
    if (!bookings.transition(b, from, actor, reason)) {
      throw new DomainException(
          "illegal-booking-transition", "The booking was changed by someone else; reload it");
    }
    events.transitioned(b, from, reason, b.updatedAt());
    return b;
  }

  private Booking load(UUID bookingId) {
    return bookings.findById(bookingId).orElseThrow(BookingService::notFound);
  }

  private Booking asHost(UUID callerId, UUID bookingId) {
    Booking b = load(bookingId);
    if (!b.isHost(callerId)) {
      throw notParty();
    }
    return b;
  }

  private Booking asDriver(UUID callerId, UUID bookingId) {
    Booking b = load(bookingId);
    if (!b.isDriver(callerId)) {
      throw notParty();
    }
    return b;
  }

  private static ApiException notFound() {
    return ApiException.notFound("Booking");
  }

  private static ApiException notParty() {
    return new ApiException(
        HttpStatus.FORBIDDEN, "forbidden", "Not allowed", "Only the booking's party may do this");
  }
}
