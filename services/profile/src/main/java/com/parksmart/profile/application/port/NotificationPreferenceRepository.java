package com.parksmart.profile.application.port;

import com.parksmart.profile.domain.NotificationPreferences;
import java.util.UUID;

public interface NotificationPreferenceRepository {

  /** Stored switches with defaults (enabled) for channels never set. */
  NotificationPreferences load(UUID accountId);

  void save(NotificationPreferences preferences);
}
