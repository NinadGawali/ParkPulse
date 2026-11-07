package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.application.port.VehicleRepository;
import com.parksmart.profile.domain.DomainException;
import com.parksmart.profile.domain.Vehicle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class JpaVehicleRepository implements VehicleRepository {

  private final VehicleJpaRepository jpa;

  JpaVehicleRepository(VehicleJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<Vehicle> findActive(UUID vehicleId) {
    return jpa.findByIdAndDeletedAtIsNull(vehicleId).map(JpaVehicleRepository::toDomain);
  }

  @Override
  public List<Vehicle> listActive(UUID ownerAccountId) {
    return jpa
        .findByOwnerAccountIdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(ownerAccountId)
        .stream()
        .map(JpaVehicleRepository::toDomain)
        .toList();
  }

  @Override
  public void save(Vehicle vehicle) {
    VehicleEntity entity;
    if (vehicle.isNew()) {
      entity = new VehicleEntity();
      entity.id = vehicle.id();
      entity.ownerAccountId = vehicle.ownerAccountId();
      entity.licensePlate = vehicle.licensePlate();
      entity.plateRegion = vehicle.plateRegion();
      entity.createdAt = vehicle.createdAt();
    } else {
      entity =
          jpa.findById(vehicle.id())
              .orElseThrow(() ->
                  new ObjectOptimisticLockingFailureException(VehicleEntity.class, vehicle.id()));
      if (entity.version != vehicle.version()) {
        throw new ObjectOptimisticLockingFailureException(VehicleEntity.class, vehicle.id());
      }
    }
    entity.vehicleType = vehicle.vehicleType();
    entity.make = vehicle.make();
    entity.model = vehicle.model();
    entity.color = vehicle.color();
    entity.primary = vehicle.primary();
    entity.updatedAt = vehicle.updatedAt();
    entity.deletedAt = vehicle.deletedAt();
    try {
      // Flush now so the partial unique indexes are checked in call order (clear the old
      // primary before setting the new one) and violations map to domain errors here.
      jpa.saveAndFlush(entity);
    } catch (DataIntegrityViolationException e) {
      if (ConstraintViolations.violated(e, "uq_plate_per_region")) {
        throw new DomainException(
            "plate-registered", "This plate is already registered in this region");
      }
      if (ConstraintViolations.violated(e, "uq_one_primary_vehicle")) {
        throw new DomainException(
            "concurrent-modification", "Vehicles were changed by another request; retry");
      }
      throw e;
    }
  }

  private static Vehicle toDomain(VehicleEntity e) {
    return Vehicle.restore(
        e.id,
        e.ownerAccountId,
        e.licensePlate,
        e.plateRegion,
        e.vehicleType,
        e.make,
        e.model,
        e.color,
        e.primary,
        e.createdAt,
        e.updatedAt,
        e.deletedAt,
        e.version);
  }
}
