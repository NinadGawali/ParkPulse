package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Listing was soft-deleted. Search removes it. */
public record ListingArchived(UUID listingId, UUID hostAccountId, Instant archivedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listingId.toString();
  }
}
