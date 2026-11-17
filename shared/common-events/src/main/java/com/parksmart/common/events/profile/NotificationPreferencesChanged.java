package com.parksmart.common.events.profile;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/**
 * A user's delivery channels changed (Profile owns them; Notification keeps a copy). IN_APP is
 * always on. Channels never set are enabled.
 */
public record NotificationPreferencesChanged(
    UUID accountId, boolean email, boolean sms, boolean push, Instant changedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
