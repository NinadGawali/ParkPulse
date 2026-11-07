package com.parksmart.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One link in a refresh-token rotation chain ({@code familyId} = one login). Presenting a revoked
 * token means it was stolen or replayed: the whole family is revoked. {@code familyStartedAt} is
 * the login time and caps the session's absolute lifetime.
 */
public record RefreshToken(
    UUID id,
    UUID accountId,
    UUID familyId,
    Instant familyStartedAt,
    String tokenHash,
    Instant issuedAt,
    Instant expiresAt,
    boolean revoked,
    UUID replacedByTokenId) {

  public RefreshToken {
    Objects.requireNonNull(id);
    Objects.requireNonNull(accountId);
    Objects.requireNonNull(familyId);
    Objects.requireNonNull(familyStartedAt);
    Objects.requireNonNull(tokenHash);
    Objects.requireNonNull(issuedAt);
    Objects.requireNonNull(expiresAt);
  }

  public static RefreshToken issue(
      UUID id,
      UUID accountId,
      UUID familyId,
      Instant familyStartedAt,
      String tokenHash,
      Instant now,
      Duration ttl) {
    return new RefreshToken(
        id, accountId, familyId, familyStartedAt, tokenHash, now, now.plus(ttl), false, null);
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
