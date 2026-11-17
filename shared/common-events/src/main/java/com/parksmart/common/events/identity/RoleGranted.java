package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** A role (DRIVER, HOST, ADMIN) was granted to the account. */
public record RoleGranted(UUID accountId, String role, Instant grantedAt) implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
