package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * An account was created (status PENDING_VERIFICATION). Carries no contact details; those go to
 * Topics.IDENTITY_PRIVATE.
 */
public record AccountRegistered(UUID accountId, Instant registeredAt) implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
