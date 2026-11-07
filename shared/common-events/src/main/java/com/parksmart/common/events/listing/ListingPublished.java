package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;

/** Listing became bookable (admin approval or resume). Search indexes the snapshot. */
public record ListingPublished(ListingSnapshot listing, Instant publishedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listing.listingId().toString();
  }
}
