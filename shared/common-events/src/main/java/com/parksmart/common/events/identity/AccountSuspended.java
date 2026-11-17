package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** An admin suspended the account; its refresh tokens are revoked. */
public record AccountSuspended(UUID accountId, String reason, Instant suspendedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
