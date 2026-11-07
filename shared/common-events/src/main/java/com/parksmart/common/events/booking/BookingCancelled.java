package com.parksmart.common.events.booking;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Driver or host cancelled (cancelledBy: DRIVER, HOST). refundAmount is computed by Booking from
 * the policy snapshot; Payment voids or refunds.
 */
public record BookingCancelled(
    UUID bookingId,
    UUID listingId,
    UUID driverAccountId,
    UUID hostAccountId,
    String cancelledBy,
    String reason,
    BigDecimal refundAmount,
    String currency,
    Instant startTime,
    Instant endTime,
    Instant cancelledAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
