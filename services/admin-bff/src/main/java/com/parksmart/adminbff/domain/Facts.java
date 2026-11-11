package com.parksmart.adminbff.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the BFF reads from the owning services, reduced to the fields admin views need. Contact
 * details (email, phone), plates, vehicle and payout data are deliberately absent.
 */
public final class Facts {

  private Facts() {}

  /** A public profile (Profile service). */
  public record Party(
      UUID accountId, String displayName, String photoUrl, String ratingAvg, int ratingCount) {
    public Party {
      Objects.requireNonNull(accountId, "accountId");
    }
  }

  /** One row of Listing's admin list. */
  public record ListingRow(
      UUID listingId,
      String title,
      String status,
      BigDecimal pricePerHour,
      String currency,
      String thumbnailUrl) {
    public ListingRow {
      Objects.requireNonNull(listingId, "listingId");
    }
  }

  /** A listing as an admin sees it (Listing service). */
  public record Listing(
      UUID listingId,
      UUID hostAccountId,
      String title,
      String status,
      String address,
      int photoCount,
      int availabilityRuleCount,
      String rejectionReason,
      Instant updatedAt) {
    public Listing {
      Objects.requireNonNull(listingId, "listingId");
      Objects.requireNonNull(hostAccountId, "hostAccountId");
    }
  }

  /** A booking (Booking service). */
  public record Booking(
      UUID bookingId,
      UUID listingId,
      UUID driverAccountId,
      UUID hostAccountId,
      String status,
      Instant startTime,
      Instant endTime,
      BigDecimal pricePerHour,
      BigDecimal totalAmount,
      String currency,
      String cancellationPolicy,
      BigDecimal refundAmount,
      Instant createdAt,
      Instant updatedAt) {
    public Booking {
      Objects.requireNonNull(bookingId, "bookingId");
      Objects.requireNonNull(listingId, "listingId");
      Objects.requireNonNull(driverAccountId, "driverAccountId");
      Objects.requireNonNull(hostAccountId, "hostAccountId");
    }
  }

  /** A payment intent (Payment service). */
  public record Payment(
      UUID intentId,
      UUID bookingId,
      BigDecimal amount,
      String currency,
      String status,
      String failureCode,
      BigDecimal refundedAmount,
      Instant updatedAt) {
    public Payment {
      Objects.requireNonNull(intentId, "intentId");
      Objects.requireNonNull(bookingId, "bookingId");
    }
  }

  /** A review after moderation (Review service). The comment is not carried. */
  public record Review(
      UUID reviewId,
      UUID bookingId,
      String direction,
      String targetType,
      UUID targetId,
      int rating) {
    public Review {
      Objects.requireNonNull(reviewId, "reviewId");
    }
  }
}
