package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Payment authorized on a request-to-book listing; the host must approve by hostDecisionDueBy. */
public record BookingAwaitingHostApproval(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    Instant startTime,
    Instant endTime,
    Instant hostDecisionDueBy,
    Instant occurredAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
