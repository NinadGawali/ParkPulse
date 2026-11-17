package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.application.port.NotificationPreferenceRepository;
import com.parksmart.profile.domain.NotificationChannel;
import com.parksmart.profile.domain.NotificationPreferences;
import com.parksmart.profile.infrastructure.persistence.NotificationPreferenceEntity.Key;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaNotificationPreferenceRepository implements NotificationPreferenceRepository {

  private final NotificationPreferenceJpaRepository jpa;
  private final Clock clock;

  JpaNotificationPreferenceRepository(NotificationPreferenceJpaRepository jpa, Clock clock) {
    this.jpa = jpa;
    this.clock = clock;
  }

  @Override
  public NotificationPreferences load(UUID accountId) {
    Map<NotificationChannel, Boolean> stored = new EnumMap<>(NotificationChannel.class);
    for (NotificationPreferenceEntity e : jpa.findByKeyAccountId(accountId)) {
      stored.put(e.key.channel, e.enabled);
    }
    return new NotificationPreferences(accountId, stored);
  }

  /** Upserts every channel; rows only change when the value does. */
  @Override
  public void save(NotificationPreferences preferences) {
    Map<NotificationChannel, NotificationPreferenceEntity> existing =
        new EnumMap<>(NotificationChannel.class);
    for (NotificationPreferenceEntity e : jpa.findByKeyAccountId(preferences.accountId())) {
      existing.put(e.key.channel, e);
    }
    preferences
        .asMap()
        .forEach((channel, enabled) -> {
          NotificationPreferenceEntity entity = existing.get(channel);
          if (entity == null) {
            entity = new NotificationPreferenceEntity(new Key(preferences.accountId(), channel));
          } else if (entity.enabled == enabled) {
            return;
          }
          entity.enabled = enabled;
          entity.updatedAt = clock.instant();
          jpa.save(entity);
        });
  }
}
