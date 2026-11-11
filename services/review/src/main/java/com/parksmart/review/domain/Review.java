package com.parksmart.review.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A review of a listing (by the driver) or of a driver (by the host), for one booking. */
public record Review(
    UUID id,
    UUID bookingId,
    Direction direction,
    UUID authorAccountId,
    TargetType targetType,
    UUID targetId,
    int rating,
    String comment,
    boolean hidden,
    Instant createdAt) {

  public enum Direction {
    DRIVER_TO_LISTING,
    HOST_TO_DRIVER
  }

  public enum TargetType {
    LISTING,
    DRIVER
  }

  /** Who may review which completed booking, until when. */
  public record Eligibility(
      UUID bookingId,
      UUID driverAccountId,
      UUID hostAccountId,
      UUID listingId,
      Instant expiresAt) {}

  public Review {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(authorAccountId, "authorAccountId");
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(targetId, "targetId");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /**
   * A new review by {@code author}: the driver reviews the listing, the host reviews the driver.
   * Anyone else is refused (the caller maps that to 403).
   */
  public static Review write(
      UUID id, Eligibility booking, UUID author, int rating, String comment, Instant now) {
    if (!now.isBefore(booking.expiresAt())) {
      throw new DomainException(
          "review-window-closed", "The review window for this booking has closed");
    }
    if (rating < 1 || rating > 5) {
      throw new DomainException("invalid-rating", "rating must be 1-5");
    }
    String text = Text.clean(comment, "comment", 1, 1000, false, true);
    if (author.equals(booking.driverAccountId())) {
      return new Review(
          id,
          booking.bookingId(),
          Direction.DRIVER_TO_LISTING,
          author,
          TargetType.LISTING,
          booking.listingId(),
          rating,
          text,
          false,
          now);
    }
    if (author.equals(booking.hostAccountId())) {
      return new Review(
          id,
          booking.bookingId(),
          Direction.HOST_TO_DRIVER,
          author,
          TargetType.DRIVER,
          booking.driverAccountId(),
          rating,
          text,
          false,
          now);
    }
    throw new DomainException("not-a-party", "Only the booking's driver or host can review it");
  }
}
