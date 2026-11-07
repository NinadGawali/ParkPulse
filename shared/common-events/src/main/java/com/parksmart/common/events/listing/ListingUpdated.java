package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;

/** A PUBLISHED or PAUSED listing's public fields changed. Search replaces its document. */
public record ListingUpdated(ListingSnapshot listing, Instant updatedAt) implements EventPayload {

  @Override
  public String partitionKey() {
    return listing.listingId().toString();
  }
}
