package com.parksmart.review.application.port;

import com.parksmart.review.domain.Review;
import com.parksmart.review.domain.Review.Eligibility;
import com.parksmart.review.domain.Review.TargetType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {

  void saveEligibility(Eligibility eligibility, Instant completedAt);

  Optional<Eligibility> eligibility(UUID bookingId);

  /** Throws {@code DomainException("review-exists")} for a second review in one direction. */
  void insert(Review review);

  Optional<Review> findById(UUID id);

  /** Returns true if the review was published and is now hidden. */
  boolean hide(UUID id, UUID adminId, String reason);

  List<Review> published(
      TargetType type, UUID targetId, Instant afterCreated, UUID afterId, int limit);

  record Aggregate(BigDecimal avg, int count) {}

  /** Serializes aggregate updates for one target (transaction-scoped lock). */
  void lockTarget(TargetType type, UUID targetId);

  /** Recomputes from published reviews and stores it. */
  Aggregate recompute(TargetType type, UUID targetId, Instant at);

  Aggregate aggregate(TargetType type, UUID targetId);
}
