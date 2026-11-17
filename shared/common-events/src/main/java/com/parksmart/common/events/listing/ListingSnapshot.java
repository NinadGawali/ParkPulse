package com.parksmart.common.events.listing;

import com.parksmart.common.events.NullableField;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Full public state of a listing, carried by events so Search can rebuild its index without calling
 * Listing (event-carried state transfer).
 */
public record ListingSnapshot(
    UUID listingId,
    UUID hostAccountId,
    String status,
    String title,
    String address,
    double latitude,
    double longitude,
    String timeZone,
    BigDecimal pricePerHour,
    String currency,
    String maxVehicleType,
    List<String> amenities,
    boolean instantBook,
    String cancellationPolicy,
    int bufferMinutes,
    @NullableField String thumbnailUrl,
    List<AvailabilityRuleData> availabilityRules,
    List<BlackoutData> blackouts) {

  public ListingSnapshot {
    amenities = amenities == null ? List.of() : List.copyOf(amenities);
    availabilityRules = availabilityRules == null ? List.of() : List.copyOf(availabilityRules);
    blackouts = blackouts == null ? List.of() : List.copyOf(blackouts);
  }
}
