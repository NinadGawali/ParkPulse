package com.parksmart.listing.api.dto;

import com.parksmart.listing.application.AvailabilityService.Quote;
import com.parksmart.listing.application.ListingService.ListingView;
import com.parksmart.listing.application.PhotoService.UploadSlot;
import com.parksmart.listing.domain.Enums.Amenity;
import com.parksmart.listing.domain.Enums.CancellationPolicy;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Enums.PhotoStatus;
import com.parksmart.listing.domain.Enums.UnbookableReason;
import com.parksmart.listing.domain.Listing;
import com.parksmart.listing.domain.ListingPhoto;
import com.parksmart.listing.domain.Values;
import com.parksmart.listing.domain.VehicleType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response bodies (docs/openapi/listing.yaml). Domain objects never leave the service. */
public final class Responses {

  private Responses() {}

  public record Location(double lat, double lng) {}

  public record Photo(UUID id, String url, int sortOrder, PhotoStatus status) {
    public static Photo of(ListingPhoto p) {
      return new Photo(p.id(), p.url(), p.sortOrder(), p.status());
    }
  }

  public record Rule(DayOfWeek dayOfWeek, String startTime, String endTime) {}

  public record Blackout(UUID id, Instant start, Instant end, String reason) {
    public static Blackout of(Values.Blackout b, boolean withReason) {
      return new Blackout(b.id(), b.start(), b.end(), withReason ? b.reason() : null);
    }
  }

  public record ListingResponse(
      UUID id,
      UUID hostAccountId,
      ListingStatus status,
      String title,
      String description,
      String address,
      Location location,
      String timeZone,
      String pricePerHour,
      String currency,
      VehicleType maxVehicleType,
      List<Amenity> amenities,
      boolean instantBook,
      CancellationPolicy cancellationPolicy,
      int bufferMinutes,
      List<Photo> photos,
      List<Rule> availabilityRules,
      List<Blackout> blackouts,
      String rejectionReason,
      Instant createdAt,
      Instant updatedAt) {

    public static ListingResponse of(ListingView view) {
      return of(view.listing(), view.privileged(), view.rejectionReason());
    }

    /** Owner view (after a mutation by the owner). */
    public static ListingResponse forOwner(Listing l) {
      return of(l, true, null);
    }

    /**
     * Non-privileged callers see READY photos only and no blackout reasons (they may be personal).
     */
    static ListingResponse of(Listing l, boolean privileged, String rejectionReason) {
      List<Photo> photos =
          (privileged ? l.photos().stream() : l.readyPhotos().stream())
              .sorted(
                  Comparator.comparing((ListingPhoto p) -> !p.isReady())
                      .thenComparingInt(ListingPhoto::sortOrder))
              .map(Photo::of)
              .toList();
      return new ListingResponse(
          l.id(),
          l.hostAccountId(),
          l.status(),
          l.title(),
          l.description(),
          l.address(),
          new Location(l.location().lat(), l.location().lng()),
          l.timeZone().getId(),
          l.pricePerHour().toPlainString(),
          l.currency(),
          l.maxVehicleType(),
          l.amenities().stream().sorted().toList(),
          l.instantBook(),
          l.cancellationPolicy(),
          l.bufferMinutes(),
          photos,
          l.rules().stream().map(r -> new Rule(r.day(), r.startTime(), r.endTime())).toList(),
          l.blackouts().stream()
              .sorted(Comparator.comparing(Values.Blackout::start))
              .map(b -> Blackout.of(b, privileged))
              .toList(),
          rejectionReason,
          l.createdAt(),
          l.updatedAt());
    }
  }

  public record ListingSummary(
      UUID id,
      String title,
      ListingStatus status,
      String thumbnailUrl,
      String pricePerHour,
      String currency) {

    public static ListingSummary of(Listing l) {
      return new ListingSummary(
          l.id(),
          l.title(),
          l.status(),
          l.thumbnailUrl().orElse(null),
          l.pricePerHour().toPlainString(),
          l.currency());
    }
  }

  public record PhotoUploadResponse(
      UUID photoId,
      String uploadUrl,
      String method,
      Map<String, String> requiredHeaders,
      Instant expiresAt) {

    public static PhotoUploadResponse of(UploadSlot slot) {
      return new PhotoUploadResponse(
          slot.photoId(),
          slot.presigned().url(),
          "PUT",
          slot.presigned().requiredHeaders(),
          slot.presigned().expiresAt());
    }
  }

  public record BookingQuote(
      UUID listingId,
      UUID hostAccountId,
      ListingStatus status,
      boolean bookable,
      UnbookableReason unbookableReason,
      String pricePerHour,
      String currency,
      boolean instantBook,
      CancellationPolicy cancellationPolicy,
      int bufferMinutes,
      VehicleType maxVehicleType,
      String timeZone) {

    public static BookingQuote of(Quote quote) {
      Listing l = quote.listing();
      return new BookingQuote(
          l.id(),
          l.hostAccountId(),
          l.status(),
          quote.bookable(),
          quote.unbookableReason().orElse(null),
          l.pricePerHour().toPlainString(),
          l.currency(),
          l.instantBook(),
          l.cancellationPolicy(),
          l.bufferMinutes(),
          l.maxVehicleType(),
          l.timeZone().getId());
    }
  }
}
