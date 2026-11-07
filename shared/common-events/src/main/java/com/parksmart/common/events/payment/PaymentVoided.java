package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Authorization released without capture. */
public record PaymentVoided(UUID intentId, UUID bookingId, String reason, Instant voidedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
