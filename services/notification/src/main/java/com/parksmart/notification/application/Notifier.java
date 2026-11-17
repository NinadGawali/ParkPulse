package com.parksmart.notification.application;

import com.parksmart.notification.application.port.NotificationStore;
import com.parksmart.notification.application.port.NotificationStore.Channel;
import com.parksmart.notification.application.port.NotificationStore.Notification;
import com.parksmart.notification.application.port.NotificationStore.Preferences;
import com.parksmart.notification.domain.Message;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a message for an account into notification rows: always one in the in-app feed, plus an
 * email when the user allows email and Identity has told us an address.
 */
@Component
public class Notifier {

  private final NotificationStore store;
  private final Clock clock;

  public Notifier(NotificationStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  void toAccount(UUID eventId, UUID accountId, Message message) {
    Instant now = clock.instant();
    store.insert(row(eventId, accountId, Channel.IN_APP, null, message, false, "SENT", now), null);
    Preferences prefs = store.preferences(accountId);
    store
        .contact(accountId)
        .filter(c -> prefs.email() && c.email() != null)
        .ifPresent(c ->
            store.insert(
                row(eventId, accountId, Channel.EMAIL, c.email(), message, false, "PENDING", now),
                now));
  }

  /** One-time codes: straight to the destination Identity names, whatever the preferences. */
  void code(UUID eventId, UUID accountId, Channel channel, String destination, Message message) {
    Instant now = clock.instant();
    store.insert(row(eventId, accountId, channel, destination, message, true, "PENDING", now), now);
  }

  private static Notification row(
      UUID eventId,
      UUID accountId,
      Channel channel,
      String destination,
      Message m,
      boolean sensitive,
      String status,
      Instant now) {
    return new Notification(
        UUID.randomUUID(),
        accountId,
        channel,
        m.template(),
        destination,
        m.title(),
        m.body(),
        sensitive,
        status,
        0,
        eventId,
        now,
        null);
  }
}
