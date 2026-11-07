package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Weekly rules or blackouts changed. Search refreshes its availability cache. */
public record AvailabilityChanged(
    UUID listingId,
    List<AvailabilityRuleData> availabilityRules,
    List<BlackoutData> blackouts,
    Instant changedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listingId.toString();
  }
}
