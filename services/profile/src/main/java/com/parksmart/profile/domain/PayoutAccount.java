package com.parksmart.profile.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** A host's payout destination, as a processor-issued token. Never raw bank or card data. */
public final class PayoutAccount {

  public static final int MAX_PER_HOST = 3;

  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{6,255}");

  private final UUID id;
  private final UUID hostAccountId;
  private final ProcessorType processorType;
  private final String processorToken;
  private final boolean verified;
  private final Instant createdAt;

  private PayoutAccount(
      UUID id,
      UUID hostAccountId,
      ProcessorType processorType,
      String processorToken,
      boolean verified,
      Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.hostAccountId = Objects.requireNonNull(hostAccountId, "hostAccountId");
    this.processorType = Objects.requireNonNull(processorType, "processorType");
    this.processorToken = Objects.requireNonNull(processorToken, "processorToken");
    this.verified = verified;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  /** A new, unverified payout account. */
  public static PayoutAccount add(
      UUID id, UUID hostAccountId, ProcessorType type, String token, Instant now) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw new DomainException(
          "invalid-processorToken", "processorToken must be 6-255 letters, digits, _ or -");
    }
    return new PayoutAccount(id, hostAccountId, type, token, false, now);
  }

  public static PayoutAccount restore(
      UUID id,
      UUID hostAccountId,
      ProcessorType processorType,
      String processorToken,
      boolean verified,
      Instant createdAt) {
    return new PayoutAccount(id, hostAccountId, processorType, processorToken, verified, createdAt);
  }

  /** Last four characters, safe to show back to the host. */
  public String tokenHint() {
    return "…" + processorToken.substring(processorToken.length() - 4);
  }

  public boolean isOwnedBy(UUID accountId) {
    return hostAccountId.equals(accountId);
  }

  public UUID id() {
    return id;
  }

  public UUID hostAccountId() {
    return hostAccountId;
  }

  public ProcessorType processorType() {
    return processorType;
  }

  public String processorToken() {
    return processorToken;
  }

  public boolean verified() {
    return verified;
  }

  public Instant createdAt() {
    return createdAt;
  }

  @Override
  public String toString() {
    // Never print the token.
    return "PayoutAccount[id=" + id + ", type=" + processorType + ", verified=" + verified + "]";
  }
}
