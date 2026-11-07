package com.parksmart.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One link in a refresh-token rotation chain ({@code familyId} = one login). Presenting a revoked
 * token means it was stolen or replayed: the whole family is revoked.
 */
public record RefreshToken(
    UUID id,
    UUID accountId,
    UUID familyId,
    String tokenHash,
    Instant issuedAt,
    Instant expiresAt,
    boolean revoked,
    UUID replacedByTokenId) {

  public RefreshToken {
    Objects.requireNonNull(id);
    Objects.requireNonNull(accountId);
    Objects.requireNonNull(familyId);
    Objects.requireNonNull(tokenHash);
    Objects.requireNonNull(issuedAt);
    Objects.requireNonNull(expiresAt);
  }

  public static RefreshToken issue(
      UUID id,
      UUID accountId,
      UUID familyId,
      String tokenHash,
      Instant now,
      java.time.Duration ttl) {
    return new RefreshToken(id, accountId, familyId, tokenHash, now, now.plus(ttl), false, null);
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
