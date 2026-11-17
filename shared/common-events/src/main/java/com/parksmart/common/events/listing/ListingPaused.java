package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Listing stopped taking new bookings. Search hides it. */
public record ListingPaused(UUID listingId, UUID hostAccountId, Instant pausedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listingId.toString();
  }
}
