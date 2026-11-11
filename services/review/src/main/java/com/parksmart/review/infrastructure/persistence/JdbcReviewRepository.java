package com.parksmart.review.infrastructure.persistence;

import com.parksmart.review.application.port.ReviewRepository;
import com.parksmart.review.domain.DomainException;
import com.parksmart.review.domain.Review;
import com.parksmart.review.domain.Review.Direction;
import com.parksmart.review.domain.Review.Eligibility;
import com.parksmart.review.domain.Review.TargetType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReviewRepository implements ReviewRepository {

  private static final String COLUMNS =
      "id, booking_id, direction, author_account_id, target_type, target_id, rating, comment,"
          + " moderation_status, created_at";

  private final JdbcClient jdbc;

  JdbcReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void saveEligibility(Eligibility e, Instant completedAt) {
    jdbc.sql(
            """
            INSERT INTO review_eligibility (booking_id, driver_account_id, host_account_id,
              listing_id, completed_at, expires_at)
            VALUES (:b, :d, :h, :l, :c, :x) ON CONFLICT DO NOTHING
            """)
        .param("b", e.bookingId())
        .param("d", e.driverAccountId())
        .param("h", e.hostAccountId())
        .param("l", e.listingId())
        .param("c", ts(completedAt))
        .param("x", ts(e.expiresAt()))
        .update();
  }

  @Override
  public Optional<Eligibility> eligibility(UUID bookingId) {
    return jdbc.sql(
            "SELECT booking_id, driver_account_id, host_account_id, listing_id, expires_at"
                + " FROM review_eligibility WHERE booking_id = :b")
        .param("b", bookingId)
        .query((rs, n) ->
            new Eligibility(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getObject(5, OffsetDateTime.class).toInstant()))
        .optional();
  }

  @Override
  public void insert(Review r) {
    try {
      jdbc.sql(
              """
              INSERT INTO reviews (id, booking_id, direction, author_account_id, target_type,
                target_id, rating, comment, created_at)
              VALUES (:id, :b, :dir, :author, :tt, :tid, :rating, :comment, :at)
              """)
          .param("id", r.id())
          .param("b", r.bookingId())
          .param("dir", r.direction().name())
          .param("author", r.authorAccountId())
          .param("tt", r.targetType().name())
          .param("tid", r.targetId())
          .param("rating", r.rating())
          .param("comment", r.comment())
          .param("at", ts(r.createdAt()))
          .update();
    } catch (DuplicateKeyException e) {
      throw new DomainException("review-exists", "You already reviewed this booking");
    }
  }

  @Override
  public Optional<Review> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM reviews WHERE id = :id")
        .param("id", id)
        .query(JdbcReviewRepository::map)
        .optional();
  }

  @Override
  public boolean hide(UUID id, UUID adminId, String reason) {
    return jdbc.sql(
                """
                UPDATE reviews SET moderation_status = 'HIDDEN', moderated_by = :admin,
                  moderation_reason = :reason
                WHERE id = :id AND moderation_status = 'PUBLISHED'
                """)
            .param("admin", adminId)
            .param("reason", reason)
            .param("id", id)
            .update()
        == 1;
  }

  @Override
  public List<Review> published(
      TargetType type, UUID targetId, Instant afterCreated, UUID afterId, int limit) {
    String keyset =
        afterCreated == null
            ? ""
            : " AND (created_at < :after OR (created_at = :after AND id < :afterId))";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM reviews WHERE target_type = :tt AND target_id = :tid"
                    + " AND moderation_status = 'PUBLISHED'"
                    + keyset
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("tt", type.name())
            .param("tid", targetId)
            .param("limit", limit);
    if (afterCreated != null) {
      spec = spec.param("after", ts(afterCreated)).param("afterId", afterId);
    }
    return spec.query(JdbcReviewRepository::map).list();
  }

  @Override
  public void lockTarget(TargetType type, UUID targetId) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
        .param("k", "rating:" + type + ":" + targetId)
        .query((rs, n) -> 1)
        .single();
  }

  @Override
  public Aggregate recompute(TargetType type, UUID targetId, Instant at) {
    Aggregate a =
        jdbc.sql(
                "SELECT avg(rating) AS avg, count(*) AS n FROM reviews WHERE target_type = :tt"
                    + " AND target_id = :tid AND moderation_status = 'PUBLISHED'")
            .param("tt", type.name())
            .param("tid", targetId)
            .query((rs, n) -> {
              BigDecimal avg = rs.getBigDecimal("avg");
              return new Aggregate(
                  avg == null ? null : avg.setScale(2, RoundingMode.HALF_UP), rs.getInt("n"));
            })
            .single();
    jdbc.sql(
            """
            INSERT INTO rating_aggregates (target_type, target_id, avg_rating, review_count, updated_at)
            VALUES (:tt, :tid, :avg, :n, :at)
            ON CONFLICT (target_type, target_id) DO UPDATE SET avg_rating = EXCLUDED.avg_rating,
              review_count = EXCLUDED.review_count, updated_at = EXCLUDED.updated_at
            """)
        .param("tt", type.name())
        .param("tid", targetId)
        .param("avg", a.avg())
        .param("n", a.count())
        .param("at", ts(at))
        .update();
    return a;
  }

  @Override
  public Aggregate aggregate(TargetType type, UUID targetId) {
    return jdbc.sql(
            "SELECT avg_rating, review_count FROM rating_aggregates WHERE target_type = :tt"
                + " AND target_id = :tid")
        .param("tt", type.name())
        .param("tid", targetId)
        .query((rs, n) -> new Aggregate(rs.getBigDecimal(1), rs.getInt(2)))
        .optional()
        .orElse(new Aggregate(null, 0));
  }

  private static Review map(ResultSet rs, int row) throws SQLException {
    return new Review(
        rs.getObject("id", UUID.class),
        rs.getObject("booking_id", UUID.class),
        Direction.valueOf(rs.getString("direction")),
        rs.getObject("author_account_id", UUID.class),
        TargetType.valueOf(rs.getString("target_type")),
        rs.getObject("target_id", UUID.class),
        rs.getInt("rating"),
        rs.getString("comment"),
        "HIDDEN".equals(rs.getString("moderation_status")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
