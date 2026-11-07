package com.parksmart.profile.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Per-channel switches for one account. Channels never set are enabled. */
public final class NotificationPreferences {

  private final UUID accountId;
  private final EnumMap<NotificationChannel, Boolean> enabled =
      new EnumMap<>(NotificationChannel.class);

  public NotificationPreferences(UUID accountId, Map<NotificationChannel, Boolean> stored) {
    this.accountId = Objects.requireNonNull(accountId, "accountId");
    for (NotificationChannel channel : NotificationChannel.values()) {
      enabled.put(channel, stored.getOrDefault(channel, Boolean.TRUE));
    }
  }

  /** IN_APP cannot be disabled: booking and payment notices always reach the in-app feed. */
  public void set(NotificationChannel channel, boolean on) {
    Objects.requireNonNull(channel, "channel");
    if (channel == NotificationChannel.IN_APP && !on) {
      throw new DomainException("channel-required", "IN_APP notifications cannot be disabled");
    }
    enabled.put(channel, on);
  }

  public boolean isEnabled(NotificationChannel channel) {
    return enabled.get(channel);
  }

  public UUID accountId() {
    return accountId;
  }

  public Map<NotificationChannel, Boolean> asMap() {
    return Collections.unmodifiableMap(enabled);
  }
}
