package com.parksmart.profile.application;

import com.parksmart.profile.application.port.NotificationPreferenceRepository;
import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.domain.NotificationChannel;
import com.parksmart.profile.domain.NotificationPreferences;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationPreferenceService {

  private final NotificationPreferenceRepository preferences;
  private final ProfileRepository profiles;
  private final Clock clock;

  public NotificationPreferenceService(
      NotificationPreferenceRepository preferences, ProfileRepository profiles, Clock clock) {
    this.preferences = preferences;
    this.profiles = profiles;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public NotificationPreferences get(UUID accountId) {
    return preferences.load(accountId);
  }

  /** Sets the given channels; others keep their value. */
  @Transactional
  public NotificationPreferences update(UUID accountId, Map<NotificationChannel, Boolean> changes) {
    profiles.lockAccount(accountId, clock.instant());
    NotificationPreferences current = preferences.load(accountId);
    changes.forEach(current::set);
    preferences.save(current);
    return current;
  }
}
