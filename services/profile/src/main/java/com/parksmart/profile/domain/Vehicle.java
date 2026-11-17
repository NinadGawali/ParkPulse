package com.parksmart.profile.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A driver's vehicle. Soft-deleted; a plate is unique per region among active vehicles. */
public final class Vehicle {

  public static final int MAX_PER_ACCOUNT = 10;

  private static final Pattern PLATE = Pattern.compile("[A-Z0-9]{2,15}");
  private static final Pattern REGION = Pattern.compile("[A-Z]{2}(-[A-Z0-9]{1,3})?");

  private final UUID id;
  private final UUID ownerAccountId;
  private final String licensePlate;
  private final String plateRegion;
  private VehicleType vehicleType;
  private String make;
  private String model;
  private String color;
  private boolean primary;
  private final Instant createdAt;
  private Instant updatedAt;
  private Instant deletedAt;
  private final long version;

  private Vehicle(
      UUID id,
      UUID ownerAccountId,
      String licensePlate,
      String plateRegion,
      VehicleType vehicleType,
      String make,
      String model,
      String color,
      boolean primary,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version) {
    this.id = Objects.requireNonNull(id, "id");
    this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId");
    this.licensePlate = Objects.requireNonNull(licensePlate, "licensePlate");
    this.plateRegion = Objects.requireNonNull(plateRegion, "plateRegion");
    this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType");
    this.make = make;
    this.model = model;
    this.color = color;
    this.primary = primary;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    this.deletedAt = deletedAt;
    this.version = version;
  }

  /** A new vehicle. The plate is normalized: upper case, spaces and hyphens removed. */
  public static Vehicle register(
      UUID id,
      UUID ownerAccountId,
      String rawPlate,
      String region,
      VehicleType type,
      String make,
      String model,
      String color,
      boolean primary,
      Instant now) {
    return new Vehicle(
        id,
        ownerAccountId,
        normalizePlate(rawPlate),
        normalizeRegion(region),
        Objects.requireNonNull(type, "vehicleType"),
        Text.clean(make, "make", 1, 60, false, false),
        Text.clean(model, "model", 1, 60, false, false),
        Text.clean(color, "color", 1, 30, false, false),
        primary,
        now,
        now,
        null,
        -1);
  }

  public static Vehicle restore(
      UUID id,
      UUID ownerAccountId,
      String licensePlate,
      String plateRegion,
      VehicleType vehicleType,
      String make,
      String model,
      String color,
      boolean primary,
      Instant createdAt,
      Instant updatedAt,
      Instant deletedAt,
      long version) {
    return new Vehicle(
        id,
        ownerAccountId,
        licensePlate,
        plateRegion,
        vehicleType,
        make,
        model,
        color,
        primary,
        createdAt,
        updatedAt,
        deletedAt,
        version);
  }

  public static String normalizePlate(String raw) {
    if (raw == null) {
      throw new DomainException("invalid-licensePlate", "licensePlate is required");
    }
    String plate = raw.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
    if (!PLATE.matcher(plate).matches()) {
      throw new DomainException(
          "invalid-licensePlate", "licensePlate must be 2-15 letters or digits");
    }
    return plate;
  }

  static String normalizeRegion(String raw) {
    String region = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
    if (!REGION.matcher(region).matches()) {
      throw new DomainException(
          "invalid-plateRegion", "plateRegion must look like IN or IN-MH (ISO 3166)");
    }
    return region;
  }

  public void updateDetails(
      VehicleType type, String newMake, String newModel, String newColor, Instant now) {
    ensureActive();
    vehicleType = Objects.requireNonNull(type, "vehicleType");
    make = Text.clean(newMake, "make", 1, 60, false, false);
    model = Text.clean(newModel, "model", 1, 60, false, false);
    color = Text.clean(newColor, "color", 1, 30, false, false);
    updatedAt = now;
  }

  public void markPrimary(Instant now) {
    ensureActive();
    if (!primary) {
      primary = true;
      updatedAt = now;
    }
  }

  public void clearPrimary(Instant now) {
    if (primary) {
      primary = false;
      updatedAt = now;
    }
  }

  /** Soft delete. Returns true if the vehicle was primary (so another one should take over). */
  public boolean remove(Instant now) {
    ensureActive();
    boolean wasPrimary = primary;
    primary = false;
    deletedAt = now;
    updatedAt = now;
    return wasPrimary;
  }

  public boolean isOwnedBy(UUID accountId) {
    return ownerAccountId.equals(accountId);
  }

  public boolean isActive() {
    return deletedAt == null;
  }

  private void ensureActive() {
    if (deletedAt != null) {
      throw new DomainException("vehicle-removed", "Vehicle has been removed");
    }
  }

  public boolean isNew() {
    return version < 0;
  }

  public UUID id() {
    return id;
  }

  public UUID ownerAccountId() {
    return ownerAccountId;
  }

  public String licensePlate() {
    return licensePlate;
  }

  public String plateRegion() {
    return plateRegion;
  }

  public VehicleType vehicleType() {
    return vehicleType;
  }

  public String make() {
    return make;
  }

  public String model() {
    return model;
  }

  public String color() {
    return color;
  }

  public boolean primary() {
    return primary;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deletedAt() {
    return deletedAt;
  }

  public long version() {
    return version;
  }
}
