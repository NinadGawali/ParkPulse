package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Refund issued; totalRefunded is cumulative for the intent. */
public record PaymentRefunded(
    UUID intentId,
    UUID bookingId,
    BigDecimal refundAmount,
    BigDecimal totalRefunded,
    String currency,
    Instant refundedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
