package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.NullableField;
import java.time.Instant;
import java.util.UUID;

/**
 * Current contact details (email, verified phone or null). Published only to
 * Topics.IDENTITY_PRIVATE and consumed only by Notification, which needs them to deliver messages.
 */
public record ContactDetailsChanged(
    UUID accountId, String email, @NullableField String verifiedPhone, Instant changedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
