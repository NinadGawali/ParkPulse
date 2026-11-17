package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Driver did not check in within the grace period; no refund. */
public record BookingNoShow(
    UUID bookingId, UUID listingId, UUID driverAccountId, UUID hostAccountId, Instant markedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
