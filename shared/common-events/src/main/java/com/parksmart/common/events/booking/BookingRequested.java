package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Slot held in PENDING_PAYMENT. Payment creates an intent for totalAmount. */
public record BookingRequested(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    Instant startTime,
    Instant endTime,
    BigDecimal totalAmount,
    String currency,
    boolean instantBook,
    Instant paymentDueBy,
    Instant requestedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
