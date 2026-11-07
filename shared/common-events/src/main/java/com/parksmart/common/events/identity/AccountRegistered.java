package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * An account was created (status PENDING_VERIFICATION). Consumed by Profile (create profile) and
 * Notification (welcome email).
 */
public record AccountRegistered(UUID accountId, String email, Instant registeredAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
