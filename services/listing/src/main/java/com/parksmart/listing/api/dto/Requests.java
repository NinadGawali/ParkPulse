package com.parksmart.listing.api.dto;

import com.parksmart.listing.domain.Enums.Amenity;
import com.parksmart.listing.domain.Enums.CancellationPolicy;
import com.parksmart.listing.domain.ListingTerms;
import com.parksmart.listing.domain.Values.GeoPoint;
import com.parksmart.listing.domain.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request bodies (docs/openapi/listing.yaml). The domain re-validates and normalizes. */
public final class Requests {

  private Requests() {}

  static final String MONEY = "\\d{1,8}(\\.\\d{1,2})?";
  static final String TIME = "([01]\\d|2[0-3]):[0-5]\\d";
  static final String END_TIME = "([01]\\d|2[0-3]):[0-5]\\d|24:00";

  public record Location(
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

    GeoPoint toDomain() {
      return new GeoPoint(lat, lng);
    }
  }

  public record CreateListing(
      @NotBlank @Size(min = 5, max = 255) String title,
      @Size(max = 5000) String description,
      @NotBlank @Size(min = 5, max = 512) String address,
      @NotNull @Valid Location location,
      @NotBlank @Size(max = 64) String timeZone,
      @NotNull @Pattern(regexp = MONEY) String pricePerHour,
      @NotNull @Pattern(regexp = "INR") String currency,
      @NotNull VehicleType maxVehicleType,
      @Size(max = 8) Set<@NotNull Amenity> amenities,
      @NotNull Boolean instantBook,
      @NotNull CancellationPolicy cancellationPolicy,
      @NotNull @Min(0) @Max(120) Integer bufferMinutes) {

    public ListingTerms toTerms() {
      return new ListingTerms(
          title,
          description,
          address,
          location.toDomain(),
          timeZone,
          new BigDecimal(pricePerHour),
          currency,
          maxVehicleType,
          amenities == null ? Set.of() : amenities,
          instantBook,
          cancellationPolicy,
          bufferMinutes);
    }
  }

  /** Partial update: omitted fields keep their value; at least one field is required. */
  public record UpdateListing(
      @Size(min = 5, max = 255) String title,
      @Size(max = 5000) String description,
      @Size(min = 5, max = 512) String address,
      @Valid Location location,
      @Size(max = 64) String timeZone,
      @Pattern(regexp = MONEY) String pricePerHour,
      @Pattern(regexp = "INR") String currency,
      VehicleType maxVehicleType,
      @Size(max = 8) Set<@NotNull Amenity> amenities,
      Boolean instantBook,
      CancellationPolicy cancellationPolicy,
      @Min(0) @Max(120) Integer bufferMinutes) {

    @AssertTrue(message = "at least one field is required")
    public boolean isNotEmpty() {
      return title != null
          || description != null
          || address != null
          || location != null
          || timeZone != null
          || pricePerHour != null
          || currency != null
          || maxVehicleType != null
          || amenities != null
          || instantBook != null
          || cancellationPolicy != null
          || bufferMinutes != null;
    }

    public ListingTerms toTerms() {
      return new ListingTerms(
          title,
          description,
          address,
          location == null ? null : location.toDomain(),
          timeZone,
          pricePerHour == null ? null : new BigDecimal(pricePerHour),
          currency,
          maxVehicleType,
          amenities,
          instantBook,
          cancellationPolicy,
          bufferMinutes);
    }
  }

  public record PhotoUpload(
      @NotNull @Pattern(regexp = "image/(jpeg|png|webp)") String contentType,
      @NotNull @Min(1) @Max(10_485_760) Long contentLength) {}

  public record PhotoOrder(@NotNull @Size(min = 1, max = 10) List<@NotNull UUID> photoIds) {}

  public record Rule(
      @NotNull DayOfWeek dayOfWeek,
      @NotNull @Pattern(regexp = TIME) String startTime,
      @NotNull @Pattern(regexp = END_TIME) String endTime) {}

  public record Rules(@NotNull @Size(max = 21) List<@Valid @NotNull Rule> rules) {}

  public record Blackout(
      @NotNull Instant start, @NotNull Instant end, @Size(max = 255) String reason) {}

  public record Moderation(@Size(min = 1, max = 500) String reason) {}

  public record Rejection(@NotBlank @Size(max = 500) String reason) {}
}
