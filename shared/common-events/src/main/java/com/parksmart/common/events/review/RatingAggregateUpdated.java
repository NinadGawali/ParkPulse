package com.parksmart.common.events.review;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** New average for a listing or driver. Search and Profile cache it. */
public record RatingAggregateUpdated(
    String targetType, UUID targetId, BigDecimal avgRating, int reviewCount, Instant updatedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return targetId.toString();
  }
}
