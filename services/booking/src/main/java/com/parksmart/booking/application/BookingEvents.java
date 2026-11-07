package com.parksmart.booking.application;

import com.parksmart.booking.application.port.EventOutbox;
import com.parksmart.booking.domain.Booking;
import com.parksmart.booking.domain.BookingStatus;
import com.parksmart.common.events.booking.BookingAwaitingHostApproval;
import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingCheckedIn;
import com.parksmart.common.events.booking.BookingCompleted;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.booking.BookingDeclined;
import com.parksmart.common.events.booking.BookingExpired;
import com.parksmart.common.events.booking.BookingNoShow;
import com.parksmart.common.events.booking.BookingRequested;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Publishes the event that matches a booking's new state (one per transition). */
@Component
public class BookingEvents {

  private final EventOutbox outbox;

  public BookingEvents(EventOutbox outbox) {
    this.outbox = outbox;
  }

  void requested(Booking b) {
    outbox.publish(
        new BookingRequested(
            b.id(),
            b.listingId(),
            b.driverAccountId(),
            b.hostAccountId(),
            b.startTime(),
            b.endTime(),
            b.totalAmount(),
            b.terms().currency(),
            b.terms().instantBook(),
            b.paymentDueBy(),
            b.createdAt()));
  }

  /** The event for the state the booking just entered from {@code from}. */
  void transitioned(Booking b, BookingStatus from, String reason, Instant at) {
    switch (b.status()) {
      case CONFIRMED ->
          outbox.publish(
              new BookingConfirmed(
                  b.id(),
                  b.listingId(),
                  b.driverAccountId(),
                  b.hostAccountId(),
                  b.startTime(),
                  b.endTime(),
                  b.blockedUntil(),
                  b.totalAmount(),
                  b.terms().currency(),
                  at));
      case AWAITING_HOST_APPROVAL ->
          outbox.publish(
              new BookingAwaitingHostApproval(
                  b.id(),
                  b.listingId(),
                  b.driverAccountId(),
                  b.hostAccountId(),
                  b.startTime(),
                  b.endTime(),
                  b.hostDecisionDueBy(),
                  at));
      case DECLINED_BY_HOST ->
          outbox.publish(
              new BookingDeclined(
                  b.id(), b.listingId(), b.driverAccountId(), b.hostAccountId(), reason, at));
      case CANCELLED_BY_DRIVER, CANCELLED_BY_HOST ->
          outbox.publish(
              new BookingCancelled(
                  b.id(),
                  b.listingId(),
                  b.driverAccountId(),
                  b.hostAccountId(),
                  b.status() == BookingStatus.CANCELLED_BY_DRIVER ? "DRIVER" : "HOST",
                  reason,
                  b.refundAmount(),
                  b.terms().currency(),
                  b.startTime(),
                  b.endTime(),
                  at));
      case EXPIRED ->
          outbox.publish(
              new BookingExpired(
                  b.id(), b.listingId(), b.driverAccountId(), b.hostAccountId(), from.name(), at));
      case CHECKED_IN ->
          outbox.publish(
              new BookingCheckedIn(
                  b.id(), b.listingId(), b.driverAccountId(), b.hostAccountId(), at));
      case COMPLETED ->
          outbox.publish(
              new BookingCompleted(
                  b.id(),
                  b.listingId(),
                  b.driverAccountId(),
                  b.hostAccountId(),
                  b.startTime(),
                  b.endTime(),
                  at,
                  at.plus(Booking.REVIEW_WINDOW)));
      case NO_SHOW ->
          outbox.publish(
              new BookingNoShow(b.id(), b.listingId(), b.driverAccountId(), b.hostAccountId(), at));
      case PAYMENT_FAILED -> {
        // No booking event: PaymentCaptureFailed (from Payment) already tells every consumer.
      }
      case PENDING_PAYMENT -> throw new IllegalStateException("no transition into PENDING_PAYMENT");
    }
  }
}
