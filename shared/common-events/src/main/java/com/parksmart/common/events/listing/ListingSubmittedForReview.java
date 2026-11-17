package com.parksmart.common.events.listing;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** Listing moved to PENDING_REVIEW (moderation queue). */
public record ListingSubmittedForReview(UUID listingId, UUID hostAccountId, Instant submittedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return listingId.toString();
  }
}
