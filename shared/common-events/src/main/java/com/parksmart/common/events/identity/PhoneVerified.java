package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * The account's phone (E.164) was verified. Consumed by Listing (host eligibility) and Notification
 * (SMS destination).
 */
public record PhoneVerified(UUID accountId, String phone, Instant verifiedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
