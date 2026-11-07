package com.parksmart.booking.application;

import com.parksmart.booking.application.port.BookingRepository;
import com.parksmart.booking.application.port.BookingRepository.Timer;
import com.parksmart.booking.application.port.ProcessedEvents;
import com.parksmart.booking.domain.Booking;
import com.parksmart.booking.domain.BookingStatus;
import com.parksmart.booking.domain.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The saga side of Booking: reactions to Payment and Listing events, and the timers. Late or
 * duplicate signals (e.g. PaymentAuthorized after EXPIRED) are recorded and ignored; Payment voids
 * on BookingExpired, so nothing is charged for a booking that lost its slot.
 */
@Service
public class BookingSaga {

  private static final Logger log = LoggerFactory.getLogger(BookingSaga.class);
  static final String CONSUMER = "booking-service";

  private final BookingRepository bookings;
  private final ProcessedEvents processed;
  private final BookingEvents events;
  private final Clock clock;

  public BookingSaga(
      BookingRepository bookings, ProcessedEvents processed, BookingEvents events, Clock clock) {
    this.bookings = bookings;
    this.processed = processed;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public void paymentAuthorized(UUID eventId, UUID bookingId) {
    onEvent(eventId, bookingId, b -> b.paymentAuthorized(clock.instant()), "payment authorized");
  }

  @Transactional
  public void paymentFailed(UUID eventId, UUID bookingId, String failureCode) {
    onEvent(
        eventId,
        bookingId,
        b -> b.paymentFailed(clock.instant()),
        "payment failed: " + failureCode);
  }

  @Transactional
  public void captureFailed(UUID eventId, UUID bookingId, String failureCode) {
    onEvent(
        eventId,
        bookingId,
        b -> b.captureFailed(clock.instant()),
        "capture failed: " + failureCode);
  }

  /** The listing was archived: end every booking on it that has not started. */
  @Transactional
  public void listingArchived(UUID eventId, UUID listingId) {
    if (!processed.markProcessed(eventId, CONSUMER)) {
      return;
    }
    for (Booking b : bookings.activeByListing(listingId)) {
      tryTransition(b, x -> x.listingWithdrawn(clock.instant()), "listing archived");
    }
  }

  private void onEvent(
      UUID eventId, UUID bookingId, Function<Booking, BookingStatus> step, String reason) {
    if (!processed.markProcessed(eventId, CONSUMER)) {
      return;
    }
    bookings
        .findById(bookingId)
        .ifPresentOrElse(
            b -> tryTransition(b, step, reason),
            () -> log.warn("Event {} for unknown booking {} ignored", eventId, bookingId));
  }

  // ---------------------------------------------------------------- timers

  /** Runs one timer over a locked batch. Returns how many bookings moved. */
  @Transactional
  public int runTimer(Timer timer, int batch) {
    Instant now = clock.instant();
    int moved = 0;
    for (Booking b : bookings.lockDue(timer, now, batch)) {
      boolean ok =
          switch (timer) {
            case PAYMENT_EXPIRY ->
                tryTransition(b, x -> x.expirePayment(now), "payment window ended");
            case HOST_DECISION_EXPIRY ->
                tryTransition(b, x -> x.expireHostDecision(now), "host did not respond");
            case NO_SHOW -> tryTransition(b, x -> x.markNoShow(now), "no check-in");
            case COMPLETION -> tryTransition(b, x -> x.completeAtEnd(now), "end time reached");
          };
      if (ok) {
        moved++;
      }
    }
    return moved;
  }

  /** A transition from a signal: illegal or lost races are no-ops. */
  private boolean tryTransition(Booking b, Function<Booking, BookingStatus> step, String reason) {
    BookingStatus from;
    try {
      from = step.apply(b);
    } catch (DomainException e) {
      log.info("Booking {} is {}; '{}' ignored", b.id(), b.status(), reason);
      return false;
    }
    if (!bookings.transition(b, from, null, reason)) {
      log.info("Booking {} changed concurrently; '{}' ignored", b.id(), reason);
      return false;
    }
    events.transitioned(b, from, reason, b.updatedAt());
    return true;
  }
}
