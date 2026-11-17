package com.parksmart.notification.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Contacts, preferences, admins and the notifications table. */
public interface NotificationStore {

  record Contact(String email, String phone) {}

  void saveContact(UUID accountId, String email, String phone, Instant at);

  Optional<Contact> contact(UUID accountId);

  record Preferences(boolean email, boolean sms, boolean push) {
    public static final Preferences DEFAULT = new Preferences(true, true, true);
  }

  void savePreferences(UUID accountId, Preferences preferences, Instant at);

  Preferences preferences(UUID accountId);

  void addAdmin(UUID accountId, Instant at);

  List<UUID> admins();

  enum Channel {
    EMAIL,
    SMS,
    IN_APP
  }

  record Notification(
      UUID id,
      UUID accountId,
      Channel channel,
      String template,
      String destination,
      String title,
      String body,
      boolean sensitive,
      String status,
      int attempts,
      UUID sourceEventId,
      Instant createdAt,
      Instant readAt) {}

  /** Idempotent: a second row for the same event, channel, account and template is ignored. */
  void insert(Notification notification, Instant nextAttemptAt);

  /** PENDING email/SMS rows due now, locked for this dispatcher (SKIP LOCKED). */
  List<Notification> lockDue(Instant now, int limit);

  void markSent(UUID id, Instant at, boolean redact);

  void markRetry(UUID id, int attempts, Instant nextAttemptAt, String error);

  void markFailed(UUID id, int attempts, String error, boolean redact);

  /** Sensitive rows still pending after {@code before} are failed and redacted (codes expire). */
  int expireSensitive(Instant before);

  List<Notification> feed(UUID accountId, Instant afterCreated, UUID afterId, int limit);

  long unread(UUID accountId);

  Optional<Notification> findInApp(UUID id);

  void markRead(UUID id, Instant at);

  int markAllRead(UUID accountId, Instant at);
}
