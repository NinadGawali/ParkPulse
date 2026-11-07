package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Authorization failed (failureCode e.g. card_declined). Booking expires the hold. */
public record PaymentFailed(UUID intentId, UUID bookingId, String failureCode, Instant failedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
