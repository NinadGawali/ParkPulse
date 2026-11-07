package com.parksmart.common.events.identity;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * A one-time code or reset token must be delivered. Published only to
 * Topics.IDENTITY_VERIFICATION_DELIVERY and consumed only by Notification. purpose: EMAIL_VERIFY,
 * PHONE_VERIFY, PASSWORD_RESET; channel: EMAIL, SMS.
 */
public record VerificationCodeIssued(
    UUID accountId,
    String purpose,
    String channel,
    String destination,
    String code,
    Instant expiresAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
