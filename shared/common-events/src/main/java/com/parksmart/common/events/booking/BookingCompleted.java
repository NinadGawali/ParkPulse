package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Booking completed. Opens review eligibility (window ends reviewWindowEndsAt). */
public record BookingCompleted(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    Instant startTime,
    Instant endTime,
    Instant completedAt,
    Instant reviewWindowEndsAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
