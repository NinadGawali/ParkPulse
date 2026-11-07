package com.parksmart.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A one-time code or reset token. Only a hash of the secret is ever held. */
public final class VerificationToken {

  public static final int MAX_ATTEMPTS = 5;

  private final UUID id;
  private final UUID accountId;
  private final VerificationPurpose purpose;
  private final String tokenHash;
  private final String destination;
  private final Instant expiresAt;
  private int attempts;
  private Instant consumedAt;

  public VerificationToken(
      UUID id,
      UUID accountId,
      VerificationPurpose purpose,
      String tokenHash,
      String destination,
      Instant expiresAt,
      int attempts,
      Instant consumedAt) {
    this.id = Objects.requireNonNull(id);
    this.accountId = Objects.requireNonNull(accountId);
    this.purpose = Objects.requireNonNull(purpose);
    this.tokenHash = Objects.requireNonNull(tokenHash);
    this.destination = destination;
    this.expiresAt = Objects.requireNonNull(expiresAt);
    this.attempts = attempts;
    this.consumedAt = consumedAt;
  }

  public static VerificationToken issue(
      UUID id,
      UUID accountId,
      VerificationPurpose purpose,
      String tokenHash,
      String destination,
      Instant now) {
    return new VerificationToken(
        id, accountId, purpose, tokenHash, destination, now.plus(purpose.ttl()), 0, null);
  }

  /**
   * Checks a submitted secret (already hashed by the caller). Wrong guesses count; after {@link
   * #MAX_ATTEMPTS} the token is burned.
   *
   * @throws DomainException {@code invalid-code} for any failure, without saying which
   */
  public void redeem(String submittedHash, Instant now) {
    if (consumedAt != null || !now.isBefore(expiresAt) || attempts >= MAX_ATTEMPTS) {
      throw invalid();
    }
    if (!constantTimeEquals(tokenHash, submittedHash)) {
      attempts++;
      if (attempts >= MAX_ATTEMPTS) {
        consumedAt = now;
      }
      throw invalid();
    }
    consumedAt = now;
  }

  public void invalidate(Instant now) {
    if (consumedAt == null) {
      consumedAt = now;
    }
  }

  private static DomainException invalid() {
    return new DomainException("invalid-code", "The code is invalid or has expired");
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < a.length(); i++) {
      result |= a.charAt(i) ^ b.charAt(i);
    }
    return result == 0;
  }

  public UUID id() {
    return id;
  }

  public UUID accountId() {
    return accountId;
  }

  public VerificationPurpose purpose() {
    return purpose;
  }

  public String tokenHash() {
    return tokenHash;
  }

  public String destination() {
    return destination;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public int attempts() {
    return attempts;
  }

  public Instant consumedAt() {
    return consumedAt;
  }
}
