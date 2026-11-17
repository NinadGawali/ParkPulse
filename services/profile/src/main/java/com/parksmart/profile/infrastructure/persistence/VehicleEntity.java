package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.domain.VehicleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vehicles")
class VehicleEntity {

  @Id UUID id;

  @Column(name = "owner_account_id", nullable = false, updatable = false)
  UUID ownerAccountId;

  @Column(name = "license_plate", nullable = false, updatable = false, length = 20)
  String licensePlate;

  @Column(name = "plate_region", nullable = false, updatable = false, length = 10)
  String plateRegion;

  @Enumerated(EnumType.STRING)
  @Column(name = "vehicle_type", nullable = false, length = 20)
  VehicleType vehicleType;

  @Column(name = "make", length = 60)
  String make;

  @Column(name = "model", length = 60)
  String model;

  @Column(name = "color", length = 30)
  String color;

  @Column(name = "is_primary", nullable = false)
  boolean primary;

  @Column(name = "created_at", nullable = false, updatable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "deleted_at")
  Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  Long version;

  protected VehicleEntity() {}
}
