package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Booking confirmed. Payment captures; Search and Listing mark the slot (blockedUntil includes the
 * buffer).
 */
public record BookingConfirmed(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    Instant startTime,
    Instant endTime,
    Instant blockedUntil,
    BigDecimal totalAmount,
    String currency,
    Instant confirmedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
