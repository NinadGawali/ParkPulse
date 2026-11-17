package com.parksmart.payment.infrastructure.persistence;

import com.parksmart.payment.application.port.IntentRepository;
import com.parksmart.payment.domain.PaymentIntent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIntentRepository implements IntentRepository {

  private static final String COLUMNS =
      """
      id, booking_id, driver_account_id, host_account_id, amount, currency, status, processor_ref,
      payment_due_by, refunded_amount, commission_amount, failure_code, created_at, updated_at,
      version
      """;

  private final JdbcClient jdbc;

  JdbcIntentRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PaymentIntent> findById(UUID id) {
    return one("SELECT " + COLUMNS + " FROM payment_intents WHERE id = :v", id);
  }

  @Override
  public Optional<PaymentIntent> findByBooking(UUID bookingId) {
    return one("SELECT " + COLUMNS + " FROM payment_intents WHERE booking_id = :v", bookingId);
  }

  @Override
  public Optional<PaymentIntent> lockById(UUID id) {
    return one("SELECT " + COLUMNS + " FROM payment_intents WHERE id = :v FOR UPDATE", id);
  }

  @Override
  public Optional<PaymentIntent> lockByBooking(UUID bookingId) {
    return one(
        "SELECT " + COLUMNS + " FROM payment_intents WHERE booking_id = :v FOR UPDATE", bookingId);
  }

  private Optional<PaymentIntent> one(String sql, UUID value) {
    return jdbc.sql(sql).param("v", value).query(JdbcIntentRepository::map).optional();
  }

  @Override
  public boolean insertIfAbsent(PaymentIntent i) {
    return jdbc.sql(
                """
                INSERT INTO payment_intents (id, booking_id, driver_account_id, host_account_id,
                  amount, currency, status, payment_due_by, refunded_amount, created_at, updated_at)
                VALUES (:id, :booking, :driver, :host, :amount, :currency, :status, :due, 0,
                  :created, :created)
                ON CONFLICT (booking_id) DO NOTHING
                """)
            .param("id", i.id())
            .param("booking", i.bookingId())
            .param("driver", i.driverAccountId())
            .param("host", i.hostAccountId())
            .param("amount", i.amount())
            .param("currency", i.currency())
            .param("status", i.status().name())
            .param("due", ts(i.paymentDueBy()))
            .param("created", ts(i.createdAt()))
            .update()
        == 1;
  }

  @Override
  public void update(PaymentIntent i) {
    int rows =
        jdbc.sql(
                """
                UPDATE payment_intents
                SET status = :status, processor_ref = :ref, refunded_amount = :refunded,
                    commission_amount = :commission, failure_code = :failure, updated_at = :updated,
                    version = version + 1
                WHERE id = :id AND version = :version
                """)
            .param("status", i.status().name())
            .param("ref", i.processorRef())
            .param("refunded", i.refundedAmount())
            .param("commission", i.commissionAmount())
            .param("failure", i.failureCode())
            .param("updated", ts(i.updatedAt()))
            .param("id", i.id())
            .param("version", i.version())
            .update();
    if (rows != 1) {
      throw new OptimisticLockingFailureException("Payment " + i.id() + " changed concurrently");
    }
    i.stored();
  }

  @Override
  public Optional<StoredKey> findKey(String scope, UUID actorId, String key) {
    return jdbc.sql(
            """
            SELECT request_hash, intent_id FROM idempotency_keys
            WHERE scope = :scope AND actor_id = :actor AND idempotency_key = :key
            """)
        .param("scope", scope)
        .param("actor", actorId)
        .param("key", key)
        .query((rs, n) -> new StoredKey(rs.getString(1), rs.getObject(2, UUID.class)))
        .optional();
  }

  @Override
  public void saveKey(
      String scope, UUID actorId, String key, String requestHash, UUID intentId, Instant at) {
    jdbc.sql(
            """
            INSERT INTO idempotency_keys
              (scope, actor_id, idempotency_key, request_hash, intent_id, created_at)
            VALUES (:scope, :actor, :key, :hash, :intent, :at)
            """)
        .param("scope", scope)
        .param("actor", actorId)
        .param("key", key)
        .param("hash", requestHash)
        .param("intent", intentId)
        .param("at", ts(at))
        .update();
  }

  private static PaymentIntent map(ResultSet rs, int row) throws SQLException {
    return PaymentIntent.restore(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        rs.getObject("driver_account_id", UUID.class),
        rs.getObject("host_account_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        PaymentIntent.Status.valueOf(rs.getString("status")),
        rs.getString("processor_ref"),
        instant(rs, "payment_due_by"),
        rs.getBigDecimal("refunded_amount"),
        rs.getBigDecimal("commission_amount"),
        rs.getString("failure_code"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getInt("version"));
  }

  static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
