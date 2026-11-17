package com.parksmart.listing.domain;

/** Holder for the listing domain's small enums (one place, documented once). */
public final class Enums {

  private Enums() {}

  public enum ListingStatus {
    DRAFT,
    PENDING_REVIEW,
    PUBLISHED,
    PAUSED,
    ARCHIVED,
    REJECTED
  }

  public enum Amenity {
    COVERED,
    GATED,
    CCTV,
    EV_CHARGER,
    SECURITY_GUARD,
    LIGHTING,
    WHEELCHAIR_ACCESSIBLE,
    TWENTY_FOUR_SEVEN_ACCESS
  }

  /** Refund rules live in common.yaml#CancellationPolicy; Booking applies them. */
  public enum CancellationPolicy {
    FLEXIBLE,
    MODERATE,
    STRICT
  }

  public enum PhotoStatus {
    PENDING_UPLOAD,
    READY
  }

  public enum ModerationDecision {
    APPROVED,
    REJECTED
  }

  /** Why a booking window cannot be booked (BookingQuote.unbookableReason). */
  public enum UnbookableReason {
    NOT_PUBLISHED,
    OUTSIDE_AVAILABILITY,
    BLACKED_OUT
  }
}
