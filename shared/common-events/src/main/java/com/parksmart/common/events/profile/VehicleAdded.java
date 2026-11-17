package com.parksmart.common.events.profile;

import com.parksmart.common.events.EventPayload;
import java.time.Instant;
import java.util.UUID;

/** A driver added a vehicle. */
public record VehicleAdded(UUID vehicleId, UUID ownerAccountId, String vehicleType, Instant addedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return ownerAccountId.toString();
  }
}
