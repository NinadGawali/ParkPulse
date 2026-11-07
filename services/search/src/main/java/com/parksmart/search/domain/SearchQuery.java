package com.parksmart.search.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A validated search request. */
public record SearchQuery(
    double lat,
    double lng,
    double radiusKm,
    Instant start,
    Instant end,
    Integer minVehicleRank,
    BigDecimal maxPricePerHour,
    Set<String> amenities,
    Boolean instantBook,
    Sort sort,
    int limit) {

  public static final double MAX_RADIUS_KM = 50;
  public static final Duration MAX_WINDOW = Duration.ofDays(31);

  /** Vehicle sizes, smallest first (common.yaml VehicleType). */
  public static final List<String> VEHICLE_TYPES =
      List.of("MOTORCYCLE", "HATCHBACK", "SEDAN", "SUV", "VAN", "TRUCK");

  public static final Set<String> AMENITIES =
      Set.of(
          "COVERED",
          "GATED",
          "CCTV",
          "EV_CHARGER",
          "SECURITY_GUARD",
          "LIGHTING",
          "WHEELCHAIR_ACCESSIBLE",
          "TWENTY_FOUR_SEVEN_ACCESS");

  public enum Sort {
    DISTANCE,
    PRICE
  }

  public SearchQuery {
    if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
      throw invalid("lat must be -90..90 and lng -180..180");
    }
    if (!(radiusKm > 0 && radiusKm <= MAX_RADIUS_KM)) {
      throw invalid("radiusKm must be > 0 and at most " + (int) MAX_RADIUS_KM);
    }
    if ((start == null) != (end == null)) {
      throw invalid("startTime and endTime go together");
    }
    if (start != null
        && (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0)) {
      throw invalid("startTime must be before endTime, at most 31 days apart");
    }
    if (maxPricePerHour != null && maxPricePerHour.signum() <= 0) {
      throw invalid("maxPricePerHour must be positive");
    }
    amenities = amenities == null ? Set.of() : Set.copyOf(amenities);
    for (String a : amenities) {
      if (!AMENITIES.contains(a)) {
        throw invalid("unknown amenity " + a);
      }
    }
  }

  public static int vehicleRank(String type) {
    int rank = VEHICLE_TYPES.indexOf(type);
    if (rank < 0) {
      throw invalid("unknown vehicleType");
    }
    return rank;
  }

  public boolean hasWindow() {
    return start != null;
  }

  private static DomainException invalid(String message) {
    return new DomainException("invalid-search", message);
  }
}
