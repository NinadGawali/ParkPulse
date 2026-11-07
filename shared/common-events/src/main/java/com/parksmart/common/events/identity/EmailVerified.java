package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** The account's email was verified; the account is now ACTIVE. */
public record EmailVerified(UUID accountId, Instant verifiedAt) implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
