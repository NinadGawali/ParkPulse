package com.parksmart.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Account aggregate: credentials, status, verified contact points, and roles. Mutations return
 * nothing; the application layer persists the aggregate and publishes events.
 */
public final class Account {

  public static final int MAX_FAILED_LOGINS = 5;
  public static final Duration LOCKOUT = Duration.ofMinutes(15);

  private final UUID id;
  private final String email;
  private final String acceptedTermsVersion;
  private final Instant createdAt;
  private final int version;
  private String passwordHash;
  private AccountStatus status;
  private String phone;
  private Instant emailVerifiedAt;
  private Instant phoneVerifiedAt;
  private int failedLoginAttempts;
  private Instant lockedUntil;
  private final Set<RoleType> roles;

  public Account(
      UUID id,
      String email,
      String passwordHash,
      AccountStatus status,
      String phone,
      Instant emailVerifiedAt,
      Instant phoneVerifiedAt,
      String acceptedTermsVersion,
      int failedLoginAttempts,
      Instant lockedUntil,
      Set<RoleType> roles,
      Instant createdAt,
      int version) {
    this.id = Objects.requireNonNull(id);
    this.email = Objects.requireNonNull(email);
    this.passwordHash = Objects.requireNonNull(passwordHash);
    this.status = Objects.requireNonNull(status);
    this.phone = phone;
    this.emailVerifiedAt = emailVerifiedAt;
    this.phoneVerifiedAt = phoneVerifiedAt;
    this.acceptedTermsVersion = Objects.requireNonNull(acceptedTermsVersion);
    this.failedLoginAttempts = failedLoginAttempts;
    this.lockedUntil = lockedUntil;
    this.roles = roles.isEmpty() ? EnumSet.noneOf(RoleType.class) : EnumSet.copyOf(roles);
    this.createdAt = Objects.requireNonNull(createdAt);
    this.version = version;
  }

  /** A new account: PENDING_VERIFICATION with role DRIVER. */
  public static Account register(
      UUID id, String email, String passwordHash, String acceptedTermsVersion, Instant now) {
    return new Account(
        id,
        email.trim(),
        passwordHash,
        AccountStatus.PENDING_VERIFICATION,
        null,
        null,
        null,
        acceptedTermsVersion,
        0,
        null,
        EnumSet.of(RoleType.DRIVER),
        now,
        0);
  }

  public boolean canLogIn() {
    return status == AccountStatus.PENDING_VERIFICATION || status == AccountStatus.ACTIVE;
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && now.isBefore(lockedUntil);
  }

  /**
   * @return true if this changed the account (first verification)
   */
  public boolean verifyEmail(Instant now) {
    if (emailVerifiedAt != null) {
      return false;
    }
    emailVerifiedAt = now;
    if (status == AccountStatus.PENDING_VERIFICATION) {
      status = AccountStatus.ACTIVE;
    }
    return true;
  }

  /**
   * @return true if HOST was newly granted
   */
  public boolean grantHost() {
    if (status != AccountStatus.ACTIVE) {
      throw new DomainException("account-not-active", "Verify your email before becoming a host");
    }
    return roles.add(RoleType.HOST);
  }

  /**
   * Starts verification of {@code newPhone}. If a different phone is currently verified it stops
   * being verified now (the old number must not keep counting towards host eligibility).
   *
   * @return true if a previously verified phone was un-verified
   */
  public boolean startPhoneChange(String newPhone) {
    if (newPhone.equals(phone)) {
      throw new DomainException("phone-already-verified", "This phone is already verified");
    }
    if (phone != null) {
      phone = null;
      phoneVerifiedAt = null;
      return true;
    }
    return false;
  }

  public void verifyPhone(String verifiedPhone, Instant now) {
    phone = Objects.requireNonNull(verifiedPhone);
    phoneVerifiedAt = now;
  }

  /** Login counters are reset by the repository (they are updated atomically in SQL). */
  public void changePassword(String newPasswordHash) {
    passwordHash = Objects.requireNonNull(newPasswordHash);
  }

  public UUID id() {
    return id;
  }

  public String email() {
    return email;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public AccountStatus status() {
    return status;
  }

  public String phone() {
    return phone;
  }

  public Instant emailVerifiedAt() {
    return emailVerifiedAt;
  }

  public Instant phoneVerifiedAt() {
    return phoneVerifiedAt;
  }

  public String acceptedTermsVersion() {
    return acceptedTermsVersion;
  }

  public int failedLoginAttempts() {
    return failedLoginAttempts;
  }

  public Instant lockedUntil() {
    return lockedUntil;
  }

  public Set<RoleType> roles() {
    return Set.copyOf(roles);
  }

  public Instant createdAt() {
    return createdAt;
  }

  public int version() {
    return version;
  }
}
