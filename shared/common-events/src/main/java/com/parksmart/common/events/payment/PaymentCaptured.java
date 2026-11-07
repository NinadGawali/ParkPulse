package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Funds captured and split; ledger entries written. */
public record PaymentCaptured(
    UUID intentId,
    UUID bookingId,
    UUID driverAccountId,
    UUID hostAccountId,
    BigDecimal amount,
    BigDecimal commissionAmount,
    BigDecimal hostNetAmount,
    String currency,
    Instant capturedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
