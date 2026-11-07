package com.parksmart.notification.infrastructure.persistence;

import com.parksmart.notification.application.port.NotificationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationStore implements NotificationStore {

  private static final String REDACTED = "[redacted]";
  private static final String COLUMNS =
      "id, account_id, channel, template, destination, title, body, sensitive, status, attempts,"
          + " source_event_id, created_at, read_at";

  private final JdbcClient jdbc;

  JdbcNotificationStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void saveContact(UUID accountId, String email, String phone, Instant at) {
    jdbc.sql(
            """
            INSERT INTO contacts (account_id, email, phone, changed_at) VALUES (:id, :email, :phone, :at)
            ON CONFLICT (account_id) DO UPDATE SET email = EXCLUDED.email, phone = EXCLUDED.phone,
              changed_at = EXCLUDED.changed_at
            WHERE contacts.changed_at < EXCLUDED.changed_at
            """)
        .param("id", accountId)
        .param("email", email)
        .param("phone", phone)
        .param("at", ts(at))
        .update();
  }

  @Override
  public Optional<Contact> contact(UUID accountId) {
    return jdbc.sql("SELECT email, phone FROM contacts WHERE account_id = :id")
        .param("id", accountId)
        .query((rs, n) -> new Contact(rs.getString("email"), rs.getString("phone")))
        .optional();
  }

  @Override
  public void savePreferences(UUID accountId, Preferences p, Instant at) {
    jdbc.sql(
            """
            INSERT INTO channel_preferences (account_id, email, sms, push, changed_at)
            VALUES (:id, :email, :sms, :push, :at)
            ON CONFLICT (account_id) DO UPDATE SET email = EXCLUDED.email, sms = EXCLUDED.sms,
              push = EXCLUDED.push, changed_at = EXCLUDED.changed_at
            WHERE channel_preferences.changed_at < EXCLUDED.changed_at
            """)
        .param("id", accountId)
        .param("email", p.email())
        .param("sms", p.sms())
        .param("push", p.push())
        .param("at", ts(at))
        .update();
  }

  @Override
  public Preferences preferences(UUID accountId) {
    return jdbc.sql("SELECT email, sms, push FROM channel_preferences WHERE account_id = :id")
        .param("id", accountId)
        .query((rs, n) -> new Preferences(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)))
        .optional()
        .orElse(Preferences.DEFAULT);
  }

  @Override
  public void addAdmin(UUID accountId, Instant at) {
    jdbc.sql("INSERT INTO admins (account_id, granted_at) VALUES (:id, :at) ON CONFLICT DO NOTHING")
        .param("id", accountId)
        .param("at", ts(at))
        .update();
  }

  @Override
  public List<UUID> admins() {
    return jdbc.sql("SELECT account_id FROM admins").query(UUID.class).list();
  }

  @Override
  public void insert(Notification n, Instant nextAttemptAt) {
    jdbc.sql(
            """
            INSERT INTO notifications (id, account_id, channel, template, destination, title, body,
              sensitive, status, attempts, next_attempt_at, source_event_id, created_at, sent_at)
            VALUES (:id, :account, :channel, :template, :destination, :title, :body, :sensitive,
              :status, 0, :next, :event, :created, :sent)
            ON CONFLICT ON CONSTRAINT uq_notification_per_event DO NOTHING
            """)
        .param("id", n.id())
        .param("account", n.accountId())
        .param("channel", n.channel().name())
        .param("template", n.template())
        .param("destination", n.destination())
        .param("title", n.title())
        .param("body", n.body())
        .param("sensitive", n.sensitive())
        .param("status", n.status())
        .param("next", ts(nextAttemptAt))
        .param("event", n.sourceEventId())
        .param("created", ts(n.createdAt()))
        .param("sent", "SENT".equals(n.status()) ? ts(n.createdAt()) : null)
        .update();
  }

  @Override
  public List<Notification> lockDue(Instant now, int limit) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM notifications WHERE status = 'PENDING'"
                + " AND next_attempt_at <= :now ORDER BY next_attempt_at LIMIT :limit"
                + " FOR UPDATE SKIP LOCKED")
        .param("now", ts(now))
        .param("limit", limit)
        .query(JdbcNotificationStore::map)
        .list();
  }

  @Override
  public void markSent(UUID id, Instant at, boolean redact) {
    jdbc.sql(
            "UPDATE notifications SET status = 'SENT', sent_at = :at, next_attempt_at = NULL,"
                + " body = CASE WHEN :redact THEN :redacted ELSE body END WHERE id = :id")
        .param("at", ts(at))
        .param("redact", redact)
        .param("redacted", REDACTED)
        .param("id", id)
        .update();
  }

  @Override
  public void markRetry(UUID id, int attempts, Instant next, String error) {
    jdbc.sql(
            "UPDATE notifications SET attempts = :a, next_attempt_at = :next, last_error = :e"
                + " WHERE id = :id")
        .param("a", attempts)
        .param("next", ts(next))
        .param("e", error)
        .param("id", id)
        .update();
  }

  @Override
  public void markFailed(UUID id, int attempts, String error, boolean redact) {
    jdbc.sql(
            "UPDATE notifications SET status = 'FAILED', attempts = :a, last_error = :e,"
                + " next_attempt_at = NULL,"
                + " body = CASE WHEN :redact THEN :redacted ELSE body END WHERE id = :id")
        .param("a", attempts)
        .param("e", error)
        .param("redact", redact)
        .param("redacted", REDACTED)
        .param("id", id)
        .update();
  }

  @Override
  public int expireSensitive(Instant before) {
    return jdbc.sql(
            "UPDATE notifications SET status = 'FAILED', body = :redacted, last_error = 'expired',"
                + " next_attempt_at = NULL"
                + " WHERE sensitive AND status = 'PENDING' AND created_at < :before")
        .param("redacted", REDACTED)
        .param("before", ts(before))
        .update();
  }

  @Override
  public List<Notification> feed(UUID accountId, Instant afterCreated, UUID afterId, int limit) {
    String keyset =
        afterCreated == null
            ? ""
            : " AND (created_at < :after OR (created_at = :after AND id < :afterId))";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM notifications WHERE account_id = :a"
                    + " AND channel = 'IN_APP'"
                    + keyset
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("a", accountId)
            .param("limit", limit);
    if (afterCreated != null) {
      spec = spec.param("after", ts(afterCreated)).param("afterId", afterId);
    }
    return spec.query(JdbcNotificationStore::map).list();
  }

  @Override
  public long unread(UUID accountId) {
    return jdbc.sql(
            "SELECT count(*) FROM notifications WHERE account_id = :a AND channel = 'IN_APP'"
                + " AND read_at IS NULL")
        .param("a", accountId)
        .query(Long.class)
        .single();
  }

  @Override
  public Optional<Notification> findInApp(UUID id) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM notifications WHERE id = :id AND channel = 'IN_APP'")
        .param("id", id)
        .query(JdbcNotificationStore::map)
        .optional();
  }

  @Override
  public void markRead(UUID id, Instant at) {
    jdbc.sql("UPDATE notifications SET read_at = :at WHERE id = :id AND read_at IS NULL")
        .param("at", ts(at))
        .param("id", id)
        .update();
  }

  @Override
  public int markAllRead(UUID accountId, Instant at) {
    return jdbc.sql(
            "UPDATE notifications SET read_at = :at WHERE account_id = :a AND channel = 'IN_APP'"
                + " AND read_at IS NULL")
        .param("at", ts(at))
        .param("a", accountId)
        .update();
  }

  private static Notification map(ResultSet rs, int row) throws SQLException {
    OffsetDateTime read = rs.getObject("read_at", OffsetDateTime.class);
    return new Notification(
        rs.getObject("id", UUID.class),
        rs.getObject("account_id", UUID.class),
        Channel.valueOf(rs.getString("channel")),
        rs.getString("template"),
        rs.getString("destination"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getBoolean("sensitive"),
        rs.getString("status"),
        rs.getInt("attempts"),
        rs.getObject("source_event_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        read == null ? null : read.toInstant());
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
