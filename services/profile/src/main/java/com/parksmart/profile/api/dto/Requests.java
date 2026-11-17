package com.parksmart.profile.api.dto;

import com.parksmart.profile.domain.NotificationChannel;
import com.parksmart.profile.domain.ProcessorType;
import com.parksmart.profile.domain.VehicleType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies (docs/openapi/profile.yaml). The domain re-validates and normalizes. */
public final class Requests {

  private Requests() {}

  public record UpdateProfile(
      @NotBlank @Size(min = 2, max = 60) String displayName,
      @Size(max = 500) String bio,
      @Size(max = 100) String city) {}

  public record PhotoUpload(
      @NotNull @Pattern(regexp = "image/(jpeg|png|webp)") String contentType,
      @NotNull @Min(1) @Max(5_242_880) Long contentLength) {}

  public record AddVehicle(
      @NotBlank @Size(min = 2, max = 20) @Pattern(regexp = "[A-Za-z0-9 -]+") String licensePlate,
      @NotNull @Pattern(regexp = "[A-Z]{2}(-[A-Z0-9]{1,3})?") String plateRegion,
      @NotNull VehicleType vehicleType,
      @Size(max = 60) String make,
      @Size(max = 60) String model,
      @Size(max = 30) String color) {}

  public record UpdateVehicle(
      @NotNull VehicleType vehicleType,
      @Size(max = 60) String make,
      @Size(max = 60) String model,
      @Size(max = 30) String color) {}

  public record AddPayoutAccount(
      @NotNull ProcessorType processorType,
      @NotNull @Size(min = 6, max = 255) @Pattern(regexp = "[A-Za-z0-9_-]+")
          String processorToken) {}

  public record Preferences(
      @NotNull @Size(min = 1, max = 4) List<@Valid @NotNull PreferenceEntry> preferences) {}

  public record PreferenceEntry(@NotNull NotificationChannel channel, @NotNull Boolean enabled) {}
}
