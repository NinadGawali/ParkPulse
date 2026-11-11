package com.parksmart.review.application;

import com.parksmart.common.events.review.RatingAggregateUpdated;
import com.parksmart.common.events.review.ReviewSubmitted;
import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.Cursor;
import com.parksmart.common.web.CursorPage;
import com.parksmart.review.application.port.EventOutbox;
import com.parksmart.review.application.port.ProcessedEvents;
import com.parksmart.review.application.port.ReviewRepository;
import com.parksmart.review.application.port.ReviewRepository.Aggregate;
import com.parksmart.review.domain.DomainException;
import com.parksmart.review.domain.Review;
import com.parksmart.review.domain.Review.Eligibility;
import com.parksmart.review.domain.Review.TargetType;
import com.parksmart.review.domain.Text;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writing, listing and moderating reviews, and keeping the aggregates (and their events) right. */
@Service
public class ReviewService {

  static final String CONSUMER = "review-service";
  private static final java.time.Duration REVIEW_WINDOW = java.time.Duration.ofDays(14);
  private static final java.time.Duration MAX_CLOCK_SKEW = java.time.Duration.ofMinutes(5);

  private final ReviewRepository reviews;
  private final ProcessedEvents processed;
  private final EventOutbox outbox;
  private final Clock clock;

  public ReviewService(
      ReviewRepository reviews, ProcessedEvents processed, EventOutbox outbox, Clock clock) {
    this.reviews = reviews;
    this.processed = processed;
    this.outbox = outbox;
    this.clock = clock;
  }

  /** BookingCompleted: the booking becomes reviewable until its review window ends. */
  @Transactional
  public void bookingCompleted(UUID eventId, Eligibility eligibility, Instant completedAt) {
    // Don't trust the event blindly: no self-reviews, no future completion, bounded window.
    if (eligibility.driverAccountId().equals(eligibility.hostAccountId())) {
      throw new DomainException("invalid-eligibility", "driver and host must differ");
    }
    Instant now = clock.instant();
    if (completedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
      throw new DomainException("invalid-eligibility", "completedAt is in the future");
    }
    Instant latest = completedAt.plus(REVIEW_WINDOW);
    Eligibility bounded =
        eligibility.expiresAt().isAfter(latest)
            ? new Eligibility(
                eligibility.bookingId(),
                eligibility.driverAccountId(),
                eligibility.hostAccountId(),
                eligibility.listingId(),
                latest)
            : eligibility;
    if (processed.markProcessed(eventId, CONSUMER)) {
      reviews.saveEligibility(bounded, completedAt);
    }
  }

  @Transactional
  public Review submit(UUID authorId, UUID bookingId, int rating, String comment) {
    Eligibility booking =
        reviews
            .eligibility(bookingId)
            .orElseThrow(() -> ApiException.notFound("Completed booking"));
    if (!authorId.equals(booking.driverAccountId()) && !authorId.equals(booking.hostAccountId())) {
      throw ApiException.forbidden("Only the booking's driver or host can review it");
    }
    Instant now = clock.instant();
    Review review = Review.write(UUID.randomUUID(), booking, authorId, rating, comment, now);
    reviews.lockTarget(review.targetType(), review.targetId());
    reviews.insert(review);
    outbox.publish(
        new ReviewSubmitted(
            review.id(),
            review.bookingId(),
            review.direction().name(),
            authorId,
            review.targetType().name(),
            review.targetId(),
            review.rating(),
            now));
    publishAggregate(review.targetType(), review.targetId());
    return review;
  }

  @Transactional
  public Review hide(AuthenticatedAccount admin, UUID reviewId, String reason) {
    if (!admin.isAdmin()) {
      throw ApiException.forbidden("Requires role ADMIN");
    }
    Review review = reviews.findById(reviewId).orElseThrow(() -> ApiException.notFound("Review"));
    reviews.lockTarget(review.targetType(), review.targetId());
    if (reviews.hide(
        reviewId, admin.accountId(), Text.clean(reason, "reason", 1, 255, true, false))) {
      publishAggregate(review.targetType(), review.targetId());
    }
    return reviews.findById(reviewId).orElseThrow();
  }

  /** Recompute under the target lock: commit order = event order = updatedAt order. */
  private void publishAggregate(TargetType type, UUID targetId) {
    Instant now = clock.instant();
    Aggregate a = reviews.recompute(type, targetId, now);
    outbox.publish(new RatingAggregateUpdated(type.name(), targetId, a.avg(), a.count(), now));
  }

  @Transactional(readOnly = true)
  public CursorPage<Review> published(
      TargetType type, UUID targetId, String cursor, Integer limit) {
    Cursor after = Cursor.decode(cursor);
    int size = Cursor.limit(limit);
    List<Review> rows =
        reviews.published(
            type,
            targetId,
            after == null ? null : after.sortKey(),
            after == null ? null : after.id(),
            size + 1);
    if (rows.size() <= size) {
      return new CursorPage<>(rows, null);
    }
    List<Review> items = rows.subList(0, size);
    Review last = items.get(size - 1);
    return new CursorPage<>(items, new Cursor(last.createdAt(), last.id()).encode());
  }

  @Transactional(readOnly = true)
  public Aggregate summary(TargetType type, UUID targetId) {
    return reviews.aggregate(type, targetId);
  }
}
