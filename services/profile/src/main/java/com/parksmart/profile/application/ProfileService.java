package com.parksmart.profile.application;

import com.parksmart.common.events.profile.ProfileUpdated;
import com.parksmart.common.web.ApiException;
import com.parksmart.profile.application.port.EventOutbox;
import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.domain.Profile;
import com.parksmart.profile.domain.ProfilePhoto;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The caller's own profile and the public view of others. */
@Service
public class ProfileService {

  private final ProfileRepository profiles;
  private final EventOutbox outbox;
  private final Clock clock;

  public ProfileService(ProfileRepository profiles, EventOutbox outbox, Clock clock) {
    this.profiles = profiles;
    this.outbox = outbox;
    this.clock = clock;
  }

  /** The caller's profile, created on the spot if AccountRegistered has not arrived yet. */
  @Transactional
  public Profile mine(UUID accountId) {
    return profiles
        .findById(accountId)
        .orElseGet(() -> {
          profiles.createIfAbsent(accountId, clock.instant());
          return profiles.findById(accountId).orElseThrow();
        });
  }

  @Transactional
  public Profile update(UUID accountId, String displayName, String bio, String city) {
    profiles.lockAccount(accountId, clock.instant());
    Profile profile = profiles.findById(accountId).orElseThrow();
    boolean publicChange = profile.updateDetails(displayName, bio, city, clock.instant());
    profiles.save(profile);
    if (publicChange) {
      outbox.publish(updatedEvent(profile));
    }
    return profile;
  }

  @Transactional(readOnly = true)
  public Profile publicProfile(UUID accountId) {
    return profiles.findById(accountId).orElseThrow(() -> ApiException.notFound("Profile"));
  }

  static ProfileUpdated updatedEvent(Profile profile) {
    return new ProfileUpdated(
        profile.accountId(),
        profile.displayName(),
        profile.photo().map(ProfilePhoto::url).orElse(null),
        profile.hasPhoto(),
        profile.updatedAt());
  }
}
