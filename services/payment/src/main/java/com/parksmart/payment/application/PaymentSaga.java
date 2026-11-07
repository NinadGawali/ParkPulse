package com.parksmart.payment.application;

import com.parksmart.payment.application.port.IntentRepository;
import com.parksmart.payment.application.port.ProcessedEvents;
import com.parksmart.payment.domain.PaymentIntent;
import com.parksmart.payment.domain.PaymentIntent.Status;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment's side of the booking saga (payment.yaml). Each handler records the event in
 * processed_events, locks the intent and checks that the event's driver, host and amount match it
 * (a mismatching event is logged and ignored).
 */
@Service
public class PaymentSaga {

  private static final Logger log = LoggerFactory.getLogger(PaymentSaga.class);
  static final String CONSUMER = "payment-service";

  private final IntentRepository intents;
  private final ProcessedEvents processed;
  private final Money money;
  private final Clock clock;

  public PaymentSaga(
      IntentRepository intents, ProcessedEvents processed, Money money, Clock clock) {
    this.intents = intents;
    this.processed = processed;
    this.money = money;
    this.clock = clock;
  }

  /** BookingRequested: one CREATED intent per booking. */
  @Transactional
  public void bookingRequested(
      UUID eventId,
      UUID bookingId,
      UUID driver,
      UUID host,
      BigDecimal amount,
      String currency,
      Instant paymentDueBy) {
    if (!processed.markProcessed(eventId, CONSUMER)) {
      return;
    }
    intents.insertIfAbsent(
        PaymentIntent.create(
            UUID.randomUUID(),
            bookingId,
            driver,
            host,
            amount,
            currency,
            paymentDueBy,
            clock.instant()));
  }

  /** BookingConfirmed: capture the authorized amount. */
  @Transactional
  public void bookingConfirmed(
      UUID eventId, UUID bookingId, UUID driver, UUID host, BigDecimal amount) {
    PaymentIntent intent = locked(eventId, bookingId, driver, host, amount);
    if (intent == null) {
      return;
    }
    if (intent.status() != Status.AUTHORIZED) {
      log.info(
          "Booking {} confirmed but payment is {}; nothing to capture", bookingId, intent.status());
      return;
    }
    money.capture(intent);
  }

  /** BookingExpired / BookingDeclined: release the hold; a CREATED intent can no longer be paid. */
  @Transactional
  public void bookingEnded(UUID eventId, UUID bookingId, UUID driver, UUID host, String reason) {
    PaymentIntent intent = locked(eventId, bookingId, driver, host, null);
    if (intent == null) {
      return;
    }
    if (intent.status() == Status.CREATED || intent.status() == Status.AUTHORIZED) {
      money.voidIntent(intent, reason);
    }
  }

  /**
   * BookingCancelled: full refund of an authorized payment = void; partial = capture, then refund;
   * captured = refund; zero = keep the money.
   */
  @Transactional
  public void bookingCancelled(
      UUID eventId, UUID bookingId, UUID driver, UUID host, BigDecimal refundAmount) {
    PaymentIntent intent = locked(eventId, bookingId, driver, host, null);
    if (intent == null) {
      return;
    }
    BigDecimal refund = refundAmount == null ? BigDecimal.ZERO : refundAmount;
    if (refund.signum() < 0 || refund.compareTo(intent.amount()) > 0 || refund.scale() > 2) {
      log.warn("Implausible refund for booking {}; ignored", bookingId);
      return;
    }
    switch (intent.status()) {
      case CREATED -> money.voidIntent(intent, "booking cancelled");
      case AUTHORIZED -> {
        if (refund.compareTo(intent.amount()) >= 0) {
          money.voidIntent(intent, "booking cancelled");
        } else if (money.capture(intent) && refund.signum() > 0) {
          money.refund(intent, refund, "CANCELLATION", null, "booking cancelled");
        }
      }
      case CAPTURED, PARTIALLY_REFUNDED -> {
        BigDecimal applicable = refund.min(intent.remaining());
        if (applicable.signum() > 0) {
          money.refund(intent, applicable, "CANCELLATION", null, "booking cancelled");
        }
      }
      default -> log.info("Booking {} cancelled; payment is {}", bookingId, intent.status());
    }
  }

  private PaymentIntent locked(
      UUID eventId, UUID bookingId, UUID driver, UUID host, BigDecimal amount) {
    if (!processed.markProcessed(eventId, CONSUMER)) {
      return null;
    }
    PaymentIntent intent = intents.lockByBooking(bookingId).orElse(null);
    if (intent == null) {
      log.warn("Booking event for {} without a payment intent ignored", bookingId);
      return null;
    }
    boolean matches =
        intent.driverAccountId().equals(driver)
            && intent.hostAccountId().equals(host)
            && (amount == null || intent.amount().compareTo(amount) == 0);
    if (!matches) {
      log.warn("Booking event does not match payment {}; ignored", intent.id());
      return null;
    }
    return intent;
  }
}
