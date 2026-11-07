package com.parksmart.profile.application.port;

import com.parksmart.profile.domain.Vehicle;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VehicleRepository {

  /** An active (not removed) vehicle. */
  Optional<Vehicle> findActive(UUID vehicleId);

  /** Active vehicles of an owner, oldest first. */
  List<Vehicle> listActive(UUID ownerAccountId);

  /**
   * Inserts or updates and flushes immediately, so constraint violations surface here: a taken
   * plate throws {@code DomainException("plate-registered")}; a lost primary race throws {@code
   * DomainException("concurrent-modification")}.
   */
  void save(Vehicle vehicle);
}
