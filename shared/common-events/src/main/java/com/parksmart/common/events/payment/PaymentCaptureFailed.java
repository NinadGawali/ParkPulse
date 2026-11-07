package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * Capture of an authorized intent failed after the booking was confirmed. Booking moves CONFIRMED
 * to PAYMENT_FAILED and frees the slot.
 */
public record PaymentCaptureFailed(
    UUID intentId,
    UUID bookingId,
    UUID driverAccountId,
    UUID hostAccountId,
    String failureCode,
    Instant failedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return bookingId.toString();
  }
}
