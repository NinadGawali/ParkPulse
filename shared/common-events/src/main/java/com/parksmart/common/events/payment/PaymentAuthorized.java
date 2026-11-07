package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Funds held for the booking. Booking moves to CONFIRMED or AWAITING_HOST_APPROVAL. */
public record PaymentAuthorized(
    UUID intentId, UUID bookingId, BigDecimal amount, String currency, Instant authorizedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
