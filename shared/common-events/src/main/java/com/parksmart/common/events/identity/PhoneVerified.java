package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * The account's phone was verified. Feeds Listing's host-eligibility projection. The number itself
 * is only on Topics.IDENTITY_PRIVATE.
 */
public record PhoneVerified(UUID accountId, Instant verifiedAt) implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
