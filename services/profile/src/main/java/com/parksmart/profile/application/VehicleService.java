package com.parksmart.profile.application;

import com.parksmart.common.events.profile.VehicleAdded;
import com.parksmart.common.events.profile.VehicleRemoved;
import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.web.ApiException;
import com.parksmart.profile.application.port.EventOutbox;
import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.application.port.VehicleRepository;
import com.parksmart.profile.domain.DomainException;
import com.parksmart.profile.domain.Vehicle;
import com.parksmart.profile.domain.VehicleType;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A driver's vehicles. Every call with a vehicle ID checks ownership (403 for anyone else). */
@Service
public class VehicleService {

  private final VehicleRepository vehicles;
  private final ProfileRepository profiles;
  private final EventOutbox outbox;
  private final Clock clock;

  public VehicleService(
      VehicleRepository vehicles, ProfileRepository profiles, EventOutbox outbox, Clock clock) {
    this.vehicles = vehicles;
    this.profiles = profiles;
    this.outbox = outbox;
    this.clock = clock;
  }

  public record VehicleDetails(VehicleType type, String make, String model, String color) {}

  /** Primary first, then oldest first. */
  @Transactional(readOnly = true)
  public List<Vehicle> list(UUID ownerAccountId) {
    return vehicles.listActive(ownerAccountId).stream()
        .sorted(Comparator.comparing((Vehicle v) -> !v.primary()))
        .toList();
  }

  /** Owner only, like every other vehicle endpoint. */
  @Transactional(readOnly = true)
  public Vehicle get(AuthenticatedAccount caller, UUID vehicleId) {
    Vehicle vehicle = vehicles.findActive(vehicleId).orElseThrow(() -> notFound());
    if (!vehicle.isOwnedBy(caller.accountId())) {
      throw ApiException.forbidden("Not your vehicle");
    }
    return vehicle;
  }

  @Transactional
  public Vehicle add(UUID ownerAccountId, String plate, String region, VehicleDetails details) {
    Instant now = clock.instant();
    profiles.lockAccount(ownerAccountId, now);
    List<Vehicle> existing = vehicles.listActive(ownerAccountId);
    if (existing.size() >= Vehicle.MAX_PER_ACCOUNT) {
      throw new DomainException(
          "vehicle-limit", "At most " + Vehicle.MAX_PER_ACCOUNT + " vehicles per account");
    }
    Vehicle vehicle =
        Vehicle.register(
            UUID.randomUUID(),
            ownerAccountId,
            plate,
            region,
            details.type(),
            details.make(),
            details.model(),
            details.color(),
            existing.isEmpty(),
            now);
    vehicles.save(vehicle);
    outbox.publish(
        new VehicleAdded(vehicle.id(), ownerAccountId, vehicle.vehicleType().name(), now));
    return vehicle;
  }

  @Transactional
  public Vehicle update(UUID callerId, UUID vehicleId, VehicleDetails details) {
    Vehicle vehicle = owned(callerId, vehicleId);
    vehicle.updateDetails(
        details.type(), details.make(), details.model(), details.color(), clock.instant());
    vehicles.save(vehicle);
    return vehicle;
  }

  /** Soft delete; the oldest remaining vehicle takes over as primary. */
  @Transactional
  public void remove(UUID callerId, UUID vehicleId) {
    profiles.lockAccount(callerId, clock.instant());
    Instant now = clock.instant();
    Vehicle vehicle = owned(callerId, vehicleId);
    boolean wasPrimary = vehicle.remove(now);
    vehicles.save(vehicle);
    if (wasPrimary) {
      vehicles.listActive(callerId).stream()
          .findFirst()
          .ifPresent(next -> {
            next.markPrimary(now);
            vehicles.save(next);
          });
    }
    outbox.publish(new VehicleRemoved(vehicleId, callerId, now));
  }

  @Transactional
  public Vehicle makePrimary(UUID callerId, UUID vehicleId) {
    profiles.lockAccount(callerId, clock.instant());
    Instant now = clock.instant();
    Vehicle vehicle = owned(callerId, vehicleId);
    if (vehicle.primary()) {
      return vehicle;
    }
    for (Vehicle other : vehicles.listActive(callerId)) {
      if (other.primary()) {
        other.clearPrimary(now);
        vehicles.save(other);
      }
    }
    vehicle.markPrimary(now);
    vehicles.save(vehicle);
    return vehicle;
  }

  /** Owner only (admins get 403 too). */
  private Vehicle owned(UUID callerId, UUID vehicleId) {
    Vehicle vehicle = vehicles.findActive(vehicleId).orElseThrow(() -> notFound());
    if (!vehicle.isOwnedBy(callerId)) {
      throw ApiException.forbidden("Not your vehicle");
    }
    return vehicle;
  }

  private static ApiException notFound() {
    return ApiException.notFound("Vehicle");
  }
}
