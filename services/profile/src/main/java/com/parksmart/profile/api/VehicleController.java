package com.parksmart.profile.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.profile.api.dto.Requests;
import com.parksmart.profile.api.dto.Responses.VehicleList;
import com.parksmart.profile.api.dto.Responses.VehicleResponse;
import com.parksmart.profile.application.VehicleService;
import com.parksmart.profile.application.VehicleService.VehicleDetails;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Ownership is checked in VehicleService (403 for a vehicle of another account). */
@RestController
@RequestMapping("/api/v1/vehicles")
class VehicleController {

  private final VehicleService vehicles;

  VehicleController(VehicleService vehicles) {
    this.vehicles = vehicles;
  }

  @GetMapping
  VehicleList list() {
    return new VehicleList(vehicles.list(callerId()).stream().map(VehicleResponse::of).toList());
  }

  @PostMapping
  ResponseEntity<VehicleResponse> add(@Valid @RequestBody Requests.AddVehicle body) {
    VehicleResponse created =
        VehicleResponse.of(
            vehicles.add(
                callerId(),
                body.licensePlate(),
                body.plateRegion(),
                new VehicleDetails(body.vehicleType(), body.make(), body.model(), body.color())));
    return ResponseEntity.created(URI.create("/api/v1/vehicles/" + created.id())).body(created);
  }

  @GetMapping("/{vehicleId}")
  VehicleResponse get(@PathVariable UUID vehicleId) {
    return VehicleResponse.of(vehicles.get(CurrentAccount.require(), vehicleId));
  }

  @PutMapping("/{vehicleId}")
  VehicleResponse update(
      @PathVariable UUID vehicleId, @Valid @RequestBody Requests.UpdateVehicle body) {
    return VehicleResponse.of(
        vehicles.update(
            callerId(),
            vehicleId,
            new VehicleDetails(body.vehicleType(), body.make(), body.model(), body.color())));
  }

  @DeleteMapping("/{vehicleId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void remove(@PathVariable UUID vehicleId) {
    vehicles.remove(callerId(), vehicleId);
  }

  @PostMapping("/{vehicleId}/primary")
  VehicleResponse makePrimary(@PathVariable UUID vehicleId) {
    return VehicleResponse.of(vehicles.makePrimary(callerId(), vehicleId));
  }

  private static UUID callerId() {
    return CurrentAccount.require().accountId();
  }
}
