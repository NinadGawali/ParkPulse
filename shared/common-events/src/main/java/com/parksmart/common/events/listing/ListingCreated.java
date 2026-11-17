package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** A host created a DRAFT listing. */
public record ListingCreated(UUID listingId, UUID hostAccountId, Instant createdAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listingId.toString();
  }
}
