package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * Payment or host decision timed out (fromStatus: PENDING_PAYMENT, AWAITING_HOST_APPROVAL). Payment
 * voids any authorization.
 */
public record BookingExpired(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    String fromStatus,
    Instant expiredAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
