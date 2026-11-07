package com.parksmart.booking.infrastructure.persistence;

import com.parksmart.booking.application.port.BookingRepository;
import com.parksmart.booking.domain.Booking;
import com.parksmart.booking.domain.BookingStatus;
import com.parksmart.booking.domain.CancellationPolicy;
import com.parksmart.booking.domain.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL: the slot is a TSTZRANGE guarded by the {@code excl_booking_overlap} exclusion
 * constraint, and every transition is a conditional update on (status, version).
 */
@Repository
class JdbcBookingRepository implements BookingRepository {

  private static final String EXCLUSION_VIOLATION = "23P01";
  private static final String UNIQUE_VIOLATION = "23505";

  private static final String COLUMNS =
      """
      id, driver_account_id, host_account_id, listing_id, vehicle_id, start_time, end_time,
      status, price_per_hour_snapshot, total_amount, currency, instant_book, cancellation_policy,
      buffer_minutes, payment_due_by, host_decision_due_by, refund_amount, idempotency_key,
      request_hash, created_at, updated_at, version
      """;

  private final JdbcClient jdbc;

  JdbcBookingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Booking> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM bookings WHERE id = :id")
        .param("id", id)
        .query(JdbcBookingRepository::map)
        .optional();
  }

  @Override
  public Optional<Booking> findByIdempotencyKey(UUID driverAccountId, String key) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM bookings WHERE driver_account_id = :driver AND idempotency_key = :key")
        .param("driver", driverAccountId)
        .param("key", key)
        .query(JdbcBookingRepository::map)
        .optional();
  }

  @Override
  public void insert(Booking b) {
    try {
      jdbc.sql(
              """
              INSERT INTO bookings (id, driver_account_id, host_account_id, listing_id, vehicle_id,
                start_time, end_time, blocked_until, blocked_range, status, price_per_hour_snapshot,
                total_amount, currency, instant_book, cancellation_policy, buffer_minutes,
                payment_due_by, idempotency_key, request_hash, created_at, updated_at, version)
              VALUES (:id, :driver, :host, :listing, :vehicle, :start, :end, :until,
                tstzrange(:start, :until, '[)'), :status, :price, :total, :currency, :instant,
                :policy, :buffer, :paymentDue, :key, :hash, :created, :created, 0)
              """)
          .param("id", b.id())
          .param("driver", b.driverAccountId())
          .param("host", b.hostAccountId())
          .param("listing", b.listingId())
          .param("vehicle", b.vehicleId())
          .param("start", ts(b.startTime()))
          .param("end", ts(b.endTime()))
          .param("until", ts(b.blockedUntil()))
          .param("status", b.status().name())
          .param("price", b.terms().pricePerHour())
          .param("total", b.totalAmount())
          .param("currency", b.terms().currency())
          .param("instant", b.terms().instantBook())
          .param("policy", b.terms().cancellationPolicy().name())
          .param("buffer", b.terms().bufferMinutes())
          .param("paymentDue", ts(b.paymentDueBy()))
          .param("key", b.idempotencyKey())
          .param("hash", b.requestHash())
          .param("created", ts(b.createdAt()))
          .update();
    } catch (DataIntegrityViolationException e) {
      String state = sqlState(e);
      if (EXCLUSION_VIOLATION.equals(state)) {
        throw new DomainException(
            "booking-overlap", "This time overlaps another booking of the listing");
      }
      if (UNIQUE_VIOLATION.equals(state) && e.getMessage().contains("uq_booking_idempotency")) {
        throw new DuplicateIdempotencyKey();
      }
      throw e;
    }
    history(b.id(), null, b.status(), b.driverAccountId(), "requested", b.createdAt());
  }

  @Override
  public boolean transition(Booking b, BookingStatus from, UUID changedBy, String reason) {
    int rows =
        jdbc.sql(
                """
                UPDATE bookings
                SET status = :to, payment_due_by = :paymentDue, host_decision_due_by = :hostDue,
                    refund_amount = :refund, updated_at = :updated, version = version + 1
                WHERE id = :id AND status = :from AND version = :version
                """)
            .param("to", b.status().name())
            .param("paymentDue", ts(b.paymentDueBy()))
            .param("hostDue", ts(b.hostDecisionDueBy()))
            .param("refund", b.refundAmount())
            .param("updated", ts(b.updatedAt()))
            .param("id", b.id())
            .param("from", from.name())
            .param("version", b.version())
            .update();
    if (rows == 1) {
      history(b.id(), from, b.status(), changedBy, reason, b.updatedAt());
    }
    return rows == 1;
  }

  private void history(
      UUID bookingId, BookingStatus from, BookingStatus to, UUID by, String reason, Instant at) {
    jdbc.sql(
            """
            INSERT INTO booking_status_history
              (id, booking_id, from_status, to_status, changed_by, reason, changed_at)
            VALUES (:id, :booking, :from, :to, :by, :reason, :at)
            """)
        .param("id", UUID.randomUUID())
        .param("booking", bookingId)
        .param("from", from == null ? null : from.name())
        .param("to", to.name())
        .param("by", by)
        .param(
            "reason", reason == null ? null : reason.substring(0, Math.min(255, reason.length())))
        .param("at", ts(at))
        .update();
  }

  @Override
  public List<Booking> page(
      Role role,
      UUID accountId,
      When when,
      BookingStatus status,
      Instant now,
      Instant afterStart,
      UUID afterId,
      int limit) {
    // Only constant fragments are concatenated; every value is a bound parameter.
    String party = role == Role.DRIVER ? "driver_account_id" : "host_account_id";
    boolean ascending = when == When.UPCOMING;
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM bookings WHERE ")
            .append(party)
            .append(" = :account");
    if (when == When.UPCOMING) {
      sql.append(" AND end_time > :now");
    } else if (when == When.PAST) {
      sql.append(" AND end_time <= :now");
    }
    if (status != null) {
      sql.append(" AND status = :status");
    }
    if (afterStart != null) {
      sql.append(
          ascending
              ? " AND (start_time > :after OR (start_time = :after AND id > :afterId))"
              : " AND (start_time < :after OR (start_time = :after AND id < :afterId))");
    }
    sql.append(ascending ? " ORDER BY start_time, id" : " ORDER BY start_time DESC, id DESC");
    sql.append(" LIMIT :limit");
    JdbcClient.StatementSpec spec =
        jdbc.sql(sql.toString()).param("account", accountId).param("limit", limit);
    if (when != When.ALL) {
      spec = spec.param("now", ts(now));
    }
    if (status != null) {
      spec = spec.param("status", status.name());
    }
    if (afterStart != null) {
      spec = spec.param("after", ts(afterStart)).param("afterId", afterId);
    }
    return spec.query(JdbcBookingRepository::map).list();
  }

  @Override
  public List<Booking> lockDue(Timer timer, Instant now, int limit) {
    String condition =
        switch (timer) {
          case PAYMENT_EXPIRY -> "status = 'PENDING_PAYMENT' AND payment_due_by <= :cutoff";
          case HOST_DECISION_EXPIRY ->
              "status = 'AWAITING_HOST_APPROVAL' AND host_decision_due_by <= :cutoff";
          case NO_SHOW -> "status = 'CONFIRMED' AND start_time <= :cutoff";
          case COMPLETION -> "status = 'CHECKED_IN' AND end_time <= :cutoff";
        };
    Instant cutoff = timer == Timer.NO_SHOW ? now.minus(Booking.CHECK_IN_CLOSES) : now;
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM bookings WHERE "
                + condition
                + " ORDER BY updated_at LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("cutoff", ts(cutoff))
        .param("limit", limit)
        .query(JdbcBookingRepository::map)
        .list();
  }

  @Override
  public List<Booking> activeByListing(UUID listingId) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM bookings WHERE listing_id = :listing AND status IN"
                + " ('PENDING_PAYMENT', 'AWAITING_HOST_APPROVAL', 'CONFIRMED', 'CHECKED_IN')"
                + " FOR UPDATE")
        .param("listing", listingId)
        .query(JdbcBookingRepository::map)
        .list();
  }

  private static Booking map(ResultSet rs, int row) throws SQLException {
    return Booking.restore(
        rs.getObject("id", UUID.class),
        rs.getObject("driver_account_id", UUID.class),
        rs.getObject("listing_id", UUID.class),
        rs.getObject("vehicle_id", UUID.class),
        instant(rs, "start_time"),
        instant(rs, "end_time"),
        new Booking.Terms(
            rs.getObject("host_account_id", UUID.class),
            rs.getBigDecimal("price_per_hour_snapshot"),
            rs.getString("currency"),
            rs.getBoolean("instant_book"),
            CancellationPolicy.valueOf(rs.getString("cancellation_policy")),
            rs.getInt("buffer_minutes")),
        rs.getBigDecimal("total_amount"),
        BookingStatus.valueOf(rs.getString("status")),
        instant(rs, "payment_due_by"),
        instant(rs, "host_decision_due_by"),
        rs.getBigDecimal("refund_amount"),
        rs.getString("idempotency_key"),
        rs.getString("request_hash"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        rs.getInt("version"));
  }

  private static String sqlState(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
