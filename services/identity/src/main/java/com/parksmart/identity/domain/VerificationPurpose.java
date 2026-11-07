package com.parksmart.identity.domain;

import java.time.Duration;

public enum VerificationPurpose {
  EMAIL_VERIFY(Duration.ofMinutes(30)),
  PHONE_VERIFY(Duration.ofMinutes(10)),
  PASSWORD_RESET(Duration.ofMinutes(30));

  private final Duration ttl;

  VerificationPurpose(Duration ttl) {
    this.ttl = ttl;
  }

  public Duration ttl() {
    return ttl;
  }
}
