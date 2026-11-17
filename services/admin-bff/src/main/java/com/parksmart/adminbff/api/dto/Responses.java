package com.parksmart.adminbff.api.dto;

import com.parksmart.adminbff.domain.BookingCase;
import com.parksmart.adminbff.domain.Facts;
import com.parksmart.adminbff.domain.ModerationQueueItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response bodies of docs/openapi/admin-bff.yaml. Money is a decimal string. */
public final class Responses {

  private Responses() {}

  public record PartySummary(
      UUID accountId, String displayName, String photoUrl, String ratingAvg, int ratingCount) {

    static PartySummary of(Facts.Party p) {
      return p == null
          ? null
          : new PartySummary(
              p.accountId(), p.displayName(), p.photoUrl(), p.ratingAvg(), p.ratingCount());
    }
  }

  public record ModerationQueueItemDto(
      UUID listingId,
      String title,
      String status,
      String pricePerHour,
      String currency,
      String thumbnailUrl,
      UUID hostAccountId,
      String address,
      Integer photoCount,
      Integer availabilityRuleCount,
      Instant lastChangedAt,
      PartySummary host,
      boolean partial) {

    public static ModerationQueueItemDto of(ModerationQueueItem item) {
      Facts.ListingRow row = item.row();
      Facts.Listing d = item.details();
      return new ModerationQueueItemDto(
          row.listingId(),
          row.title(),
          row.status(),
          money(row.pricePerHour()),
          row.currency(),
          row.thumbnailUrl(),
          d == null ? null : d.hostAccountId(),
          d == null ? null : d.address(),
          d == null ? null : d.photoCount(),
          d == null ? null : d.availabilityRuleCount(),
          d == null ? null : d.updatedAt(),
          PartySummary.of(item.host()),
          item.partial());
    }
  }

  public record ListingModerationOutcome(
      UUID listingId,
      UUID hostAccountId,
      String status,
      String rejectionReason,
      Instant updatedAt) {

    public static ListingModerationOutcome of(Facts.Listing l) {
      return new ListingModerationOutcome(
          l.listingId(), l.hostAccountId(), l.status(), l.rejectionReason(), l.updatedAt());
    }
  }

  public record ReviewModerationOutcome(
      UUID reviewId,
      UUID bookingId,
      String direction,
      String targetType,
      UUID targetId,
      int rating,
      boolean hidden) {

    public static ReviewModerationOutcome of(Facts.Review r) {
      return new ReviewModerationOutcome(
          r.reviewId(),
          r.bookingId(),
          r.direction(),
          r.targetType(),
          r.targetId(),
          r.rating(),
          true);
    }
  }

  public record BookingFacts(
      UUID bookingId,
      UUID listingId,
      UUID driverAccountId,
      UUID hostAccountId,
      String status,
      Instant startTime,
      Instant endTime,
      String pricePerHour,
      String totalAmount,
      String currency,
      String cancellationPolicy,
      String refundAmount,
      Instant createdAt,
      Instant updatedAt) {

    static BookingFacts of(Facts.Booking b) {
      return new BookingFacts(
          b.bookingId(),
          b.listingId(),
          b.driverAccountId(),
          b.hostAccountId(),
          b.status(),
          b.startTime(),
          b.endTime(),
          money(b.pricePerHour()),
          money(b.totalAmount()),
          b.currency(),
          b.cancellationPolicy(),
          money(b.refundAmount()),
          b.createdAt(),
          b.updatedAt());
    }
  }

  public record PaymentFacts(
      UUID intentId,
      UUID bookingId,
      String amount,
      String currency,
      String status,
      String failureCode,
      String refundedAmount,
      Instant updatedAt) {

    public static PaymentFacts of(Facts.Payment p) {
      return p == null
          ? null
          : new PaymentFacts(
              p.intentId(),
              p.bookingId(),
              money(p.amount()),
              p.currency(),
              p.status(),
              p.failureCode(),
              money(p.refundedAmount()),
              p.updatedAt());
    }
  }

  public record ListingFacts(UUID listingId, String title, String status, String address) {

    static ListingFacts of(Facts.Listing l) {
      return l == null ? null : new ListingFacts(l.listingId(), l.title(), l.status(), l.address());
    }
  }

  public record BookingCaseDto(
      BookingFacts booking,
      PaymentFacts payment,
      ListingFacts listing,
      PartySummary driver,
      PartySummary host,
      boolean partial) {

    public static BookingCaseDto of(BookingCase c) {
      return new BookingCaseDto(
          BookingFacts.of(c.booking()),
          PaymentFacts.of(c.payment()),
          ListingFacts.of(c.listing()),
          PartySummary.of(c.driver()),
          PartySummary.of(c.host()),
          c.partial());
    }
  }

  private static String money(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
