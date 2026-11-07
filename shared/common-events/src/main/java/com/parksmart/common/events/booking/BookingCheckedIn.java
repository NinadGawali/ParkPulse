package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Driver checked in. */
public record BookingCheckedIn(
    UUID bookingId, UUID listingId, UUID driverAccountId, UUID hostAccountId, Instant checkedInAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
