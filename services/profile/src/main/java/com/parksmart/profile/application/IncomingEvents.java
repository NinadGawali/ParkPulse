package com.parksmart.profile.application;

import com.parksmart.common.events.identity.AccountRegistered;
import com.parksmart.common.events.review.RatingAggregateUpdated;
import com.parksmart.profile.application.port.ProcessedEvents;
import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.domain.Profile;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Handles consumed events. Each handler is idempotent (processed_events + natural idempotency). */
@Service
public class IncomingEvents {

  static final String CONSUMER = "profile-service";

  /** Review Service's target type for ratings of drivers. */
  static final String DRIVER_TARGET = "DRIVER";

  private final ProfileRepository profiles;
  private final ProcessedEvents processed;
  private final Clock clock;

  public IncomingEvents(ProfileRepository profiles, ProcessedEvents processed, Clock clock) {
    this.profiles = profiles;
    this.processed = processed;
    this.clock = clock;
  }

  @Transactional
  public void accountRegistered(UUID eventId, AccountRegistered event) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      profiles.createIfAbsent(event.accountId(), clock.instant());
    }
  }

  /** Caches driver ratings; listing ratings are Search's concern. Older values never win. */
  @Transactional
  public void ratingAggregateUpdated(UUID eventId, RatingAggregateUpdated event) {
    if (!DRIVER_TARGET.equals(event.targetType()) || !processed.markProcessed(eventId, CONSUMER)) {
      return;
    }
    profiles.lockAccount(event.targetId(), clock.instant());
    Profile profile = profiles.findById(event.targetId()).orElseThrow();
    if (profile.applyRating(event.avgRating(), event.reviewCount(), event.updatedAt())) {
      profiles.save(profile);
    }
  }
}
