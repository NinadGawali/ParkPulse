package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * The account started changing its phone number, so the phone is no longer verified. Listing marks
 * the host ineligible to submit.
 */
public record PhoneUnverified(UUID accountId, Instant unverifiedAt) implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
