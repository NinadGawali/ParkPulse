package com.parksmart.common.events.profile;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** A driver removed a vehicle. */
public record VehicleRemoved(UUID vehicleId, UUID ownerAccountId, Instant removedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return ownerAccountId.toString();
  }
}
