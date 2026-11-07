package com.parksmart.listing.domain;

import com.parksmart.listing.domain.Enums.Amenity;
import com.parksmart.listing.domain.Enums.CancellationPolicy;
import com.parksmart.listing.domain.Values.GeoPoint;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Host-editable listing fields. For create every field except description and amenities is
 * required; for a partial update a null field means "unchanged" (an empty description clears it).
 */
public record ListingTerms(
    String title,
    String description,
    String address,
    GeoPoint location,
    String timeZone,
    BigDecimal pricePerHour,
    String currency,
    VehicleType maxVehicleType,
    Set<Amenity> amenities,
    Boolean instantBook,
    CancellationPolicy cancellationPolicy,
    Integer bufferMinutes) {}
