package com.parksmart.listing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Small value types of the listing domain. */
public final class Values {

  private Values() {}

  public record GeoPoint(double lat, double lng) {
    public GeoPoint {
      if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
        throw new DomainException("invalid-location", "lat must be -90..90 and lng -180..180");
      }
    }
  }

  /** Allowed hourly price range (configurable; default 5.00 - 2000.00 INR). */
  public record PriceBounds(BigDecimal min, BigDecimal max) {
    public PriceBounds {
      Objects.requireNonNull(min, "min");
      Objects.requireNonNull(max, "max");
    }

    void check(BigDecimal price) {
      if (price.scale() > 2 || price.compareTo(min) < 0 || price.compareTo(max) > 0) {
        throw new DomainException(
            "invalid-pricePerHour",
            "pricePerHour must be "
                + min.toPlainString()
                + "-"
                + max.toPlainString()
                + " with at most 2 decimals");
      }
    }
  }

  /** What Listing knows about a host from Identity/Profile events. */
  public record HostEligibility(boolean phoneVerified, boolean hasProfilePhoto, boolean suspended) {
    /** No events seen yet: not eligible. */
    public static final HostEligibility UNKNOWN = new HostEligibility(false, false, false);
  }

  /** A one-off unavailable window {@code [start, end)}. */
  public record Blackout(UUID id, Instant start, Instant end, String reason) {
    public Blackout {
      Objects.requireNonNull(id, "id");
      if (start == null || end == null || !start.isBefore(end)) {
        throw new DomainException("invalid-blackout", "start must be before end");
      }
      reason = Text.clean(reason, "reason", 1, 255, false, false);
    }

    boolean overlaps(Instant from, Instant to) {
      return start.isBefore(to) && from.isBefore(end);
    }
  }
}
