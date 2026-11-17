package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Moderation rejected the listing; reason is shown to the host. */
public record ListingRejected(UUID listingId, UUID hostAccountId, String reason, Instant rejectedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listingId.toString();
  }
}
