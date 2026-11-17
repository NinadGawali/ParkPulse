package com.parksmart.common.events.profile;

import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.NullableField;
import java.time.Instant;
import java.util.UUID;

/** Public profile changed. hasPhoto feeds Listing's host-eligibility projection. */
public record ProfileUpdated(
    UUID accountId,
    @NullableField String displayName,
    @NullableField String photoUrl,
    boolean hasPhoto,
    Instant updatedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return accountId.toString();
  }
}
