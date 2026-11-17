package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Host declined a request-to-book; Payment voids the authorization. */
public record BookingDeclined(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    String reason,
    Instant declinedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
