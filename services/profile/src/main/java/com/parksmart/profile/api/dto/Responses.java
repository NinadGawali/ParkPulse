package com.parksmart.profile.api.dto;

import com.parksmart.profile.application.ProfilePhotoService.UploadSlot;
import com.parksmart.profile.domain.DriverRating;
import com.parksmart.profile.domain.NotificationPreferences;
import com.parksmart.profile.domain.PayoutAccount;
import com.parksmart.profile.domain.ProcessorType;
import com.parksmart.profile.domain.Profile;
import com.parksmart.profile.domain.ProfilePhoto;
import com.parksmart.profile.domain.Vehicle;
import com.parksmart.profile.domain.VehicleType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response bodies (docs/openapi/profile.yaml). Domain objects never leave the service. */
public final class Responses {

  private Responses() {}

  public record ProfileResponse(
      UUID accountId,
      String displayName,
      String bio,
      String city,
      String photoUrl,
      boolean hasPhoto,
      String ratingAvg,
      int ratingCount,
      Instant updatedAt) {

    public static ProfileResponse of(Profile p) {
      return new ProfileResponse(
          p.accountId(),
          p.displayName(),
          p.bio(),
          p.city(),
          p.photo().map(ProfilePhoto::url).orElse(null),
          p.hasPhoto(),
          rating(p.rating()),
          p.rating().count(),
          p.updatedAt());
    }
  }

  /** The only shape other users ever see: no bio, city or contact details. */
  public record PublicProfile(
      UUID accountId, String displayName, String photoUrl, String ratingAvg, int ratingCount) {

    public static PublicProfile of(Profile p) {
      return new PublicProfile(
          p.accountId(),
          p.displayName(),
          p.photo().map(ProfilePhoto::url).orElse(null),
          rating(p.rating()),
          p.rating().count());
    }
  }

  public record PhotoUploadResponse(
      UUID uploadId,
      String uploadUrl,
      String method,
      Map<String, String> requiredHeaders,
      Instant expiresAt) {

    public static PhotoUploadResponse of(UploadSlot slot) {
      return new PhotoUploadResponse(
          slot.uploadId(),
          slot.presigned().url(),
          "PUT",
          slot.presigned().requiredHeaders(),
          slot.presigned().expiresAt());
    }
  }

  public record VehicleResponse(
      UUID id,
      String licensePlate,
      String plateRegion,
      VehicleType vehicleType,
      String make,
      String model,
      String color,
      boolean primary,
      Instant createdAt) {

    public static VehicleResponse of(Vehicle v) {
      return new VehicleResponse(
          v.id(),
          v.licensePlate(),
          v.plateRegion(),
          v.vehicleType(),
          v.make(),
          v.model(),
          v.color(),
          v.primary(),
          v.createdAt());
    }
  }

  public record VehicleList(List<VehicleResponse> items) {}

  public record PayoutAccountResponse(
      UUID id, ProcessorType processorType, String tokenHint, boolean verified, Instant createdAt) {

    public static PayoutAccountResponse of(PayoutAccount a) {
      return new PayoutAccountResponse(
          a.id(), a.processorType(), a.tokenHint(), a.verified(), a.createdAt());
    }
  }

  public record PayoutAccountList(List<PayoutAccountResponse> items) {}

  public static Requests.Preferences preferences(NotificationPreferences preferences) {
    return new Requests.Preferences(
        preferences.asMap().entrySet().stream()
            .map(e -> new Requests.PreferenceEntry(e.getKey(), e.getValue()))
            .toList());
  }

  private static String rating(DriverRating rating) {
    return rating.average() == null ? null : rating.average().toPlainString();
  }
}
