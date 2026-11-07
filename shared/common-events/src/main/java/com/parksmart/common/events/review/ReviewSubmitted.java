package com.parksmart.common.events.review;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * A review was published (direction: DRIVER_TO_LISTING, HOST_TO_DRIVER; targetType: LISTING,
 * DRIVER).
 */
public record ReviewSubmitted(
    UUID reviewId,
    UUID bookingId,
    String direction,
    UUID authorAccountId,
    String targetType,
    UUID targetId,
    int rating,
    Instant submittedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return targetId.toString();
  }
}
