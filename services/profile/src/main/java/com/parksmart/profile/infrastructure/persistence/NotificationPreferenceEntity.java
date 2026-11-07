package com.parksmart.profile.infrastructure.persistence;

import com.parksmart.profile.domain.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notification_preferences")
class NotificationPreferenceEntity {

  @EmbeddedId Key key;

  @Column(name = "enabled", nullable = false)
  boolean enabled;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected NotificationPreferenceEntity() {}

  NotificationPreferenceEntity(Key key) {
    this.key = key;
  }

  @Embeddable
  static class Key implements Serializable {

    @Column(name = "account_id")
    UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 20)
    NotificationChannel channel;

    protected Key() {}

    Key(UUID accountId, NotificationChannel channel) {
      this.accountId = accountId;
      this.channel = channel;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && accountId.equals(k.accountId) && channel == k.channel;
    }

    @Override
    public int hashCode() {
      return Objects.hash(accountId, channel);
    }
  }
}
