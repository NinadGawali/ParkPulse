package com.parksmart.payment.application;

import com.parksmart.common.events.payment.PaymentAuthorized;
import com.parksmart.common.events.payment.PaymentCaptureFailed;
import com.parksmart.common.events.payment.PaymentCaptured;
import com.parksmart.common.events.payment.PaymentFailed;
import com.parksmart.common.events.payment.PaymentRefunded;
import com.parksmart.common.events.payment.PaymentVoided;
import com.parksmart.payment.application.port.EventOutbox;
import com.parksmart.payment.application.port.IntentRepository;
import com.parksmart.payment.application.port.LedgerRepository;
import com.parksmart.payment.application.port.PaymentProcessor;
import com.parksmart.payment.domain.Ledger;
import com.parksmart.payment.domain.PaymentIntent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The money operations shared by the API and the saga. Callers hold the intent's row lock; every
 * operation calls the processor with ParkSmart's ID as idempotency key, updates the intent, writes
 * balanced ledger entries and the outbox event in the same transaction.
 */
@Component
public class Money {

  /** The processor could not complete an operation; retried (then dead-lettered) by Kafka. */
  public static final class ProcessorFailure extends RuntimeException {
    ProcessorFailure(String message) {
      super(message);
    }
  }

  private final IntentRepository intents;
  private final LedgerRepository ledger;
  private final PaymentProcessor processor;
  private final EventOutbox outbox;
  private final Clock clock;

  public Money(
      IntentRepository intents,
      LedgerRepository ledger,
      PaymentProcessor processor,
      EventOutbox outbox,
      Clock clock) {
    this.intents = intents;
    this.ledger = ledger;
    this.processor = processor;
    this.outbox = outbox;
    this.clock = clock;
  }

  void authorize(PaymentIntent intent, String paymentMethodToken) {
    PaymentProcessor.Result result =
        processor.authorize(intent.id(), paymentMethodToken, intent.amount(), intent.currency());
    Instant now = clock.instant();
    if (result.ok()) {
      intent.authorized(result.reference(), now);
      outbox.publish(
          new PaymentAuthorized(
              intent.id(),
              intent.bookingId(),
              intent.driverAccountId(),
              intent.hostAccountId(),
              intent.amount(),
              intent.currency(),
              now));
    } else {
      intent.authorizationFailed(result.failureCode(), now);
      outbox.publish(
          new PaymentFailed(
              intent.id(),
              intent.bookingId(),
              intent.driverAccountId(),
              intent.hostAccountId(),
              result.failureCode(),
              now));
    }
    intents.update(intent);
  }

  /** Capture the full amount; commission at the rate effective now. Returns true on success. */
  boolean capture(PaymentIntent intent) {
    Instant now = clock.instant();
    PaymentProcessor.Result result =
        processor.capture(intent.id(), intent.processorRef(), intent.amount());
    if (!result.ok()) {
      // Release the hold: the money will not be taken.
      processor.voidAuthorization(intent.id(), intent.processorRef());
      intent.captureFailed(result.failureCode(), now);
      intents.update(intent);
      outbox.publish(
          new PaymentCaptureFailed(
              intent.id(),
              intent.bookingId(),
              intent.driverAccountId(),
              intent.hostAccountId(),
              result.failureCode(),
              now));
      return false;
    }
    BigDecimal commission = Ledger.commission(intent.amount(), ledger.commissionRateAt(now));
    intent.captured(commission, now);
    intents.update(intent);
    ledger.post(intent, "CAPTURE", intent.id(), Ledger.capture(intent, commission), now);
    outbox.publish(
        new PaymentCaptured(
            intent.id(),
            intent.bookingId(),
            intent.driverAccountId(),
            intent.hostAccountId(),
            intent.amount(),
            commission,
            intent.amount().subtract(commission),
            intent.currency(),
            now));
    return true;
  }

  void voidIntent(PaymentIntent intent, String reason) {
    if (intent.status() == PaymentIntent.Status.AUTHORIZED) {
      processor.voidAuthorization(intent.id(), intent.processorRef());
    }
    Instant now = clock.instant();
    intent.voided(now);
    intents.update(intent);
    outbox.publish(
        new PaymentVoided(
            intent.id(),
            intent.bookingId(),
            intent.driverAccountId(),
            intent.hostAccountId(),
            reason,
            now));
  }

  /** Refunds {@code amount} of a captured intent; pro-rata ledger reversal. */
  void refund(PaymentIntent intent, BigDecimal amount, String source, UUID actor, String reason) {
    intent.checkRefundable(amount);
    BigDecimal before = intent.refundedAmount();
    // Deterministic: a retried refund (Kafka redelivery, client retry after a rollback) sends the
    // processor the same idempotency key, so it can never refund twice.
    UUID refundId =
        UUID.nameUUIDFromBytes(
            (intent.id() + "|" + before.toPlainString() + "|" + amount.toPlainString())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    PaymentProcessor.Result result = processor.refund(refundId, intent.processorRef(), amount);
    if (!result.ok()) {
      throw new ProcessorFailure("refund rejected: " + result.failureCode());
    }
    Instant now = clock.instant();
    ledger.recordRefund(refundId, intent.id(), amount, source, actor, reason, now);
    BigDecimal applied = intent.refunded(amount, now);
    intents.update(intent);
    ledger.post(intent, "REFUND", refundId, Ledger.refund(intent, applied, before), now);
    outbox.publish(
        new PaymentRefunded(
            intent.id(),
            intent.bookingId(),
            intent.driverAccountId(),
            intent.hostAccountId(),
            applied,
            intent.refundedAmount(),
            intent.currency(),
            now));
  }
}
