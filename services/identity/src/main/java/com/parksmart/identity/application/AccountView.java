package com.parksmart.identity.application;

import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.AccountStatus;
import com.parksmart.identity.domain.RoleType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model of an account for the owner. Never contains the password hash. */
public record AccountView(
    UUID id,
    String email,
    String phone,
    boolean emailVerified,
    boolean phoneVerified,
    AccountStatus status,
    List<RoleType> roles,
    Instant createdAt) {

  public static AccountView of(Account account) {
    return new AccountView(
        account.id(),
        account.email(),
        account.phone(),
        account.emailVerifiedAt() != null,
        account.phoneVerifiedAt() != null,
        account.status(),
        account.roles().stream().sorted().toList(),
        account.createdAt());
  }
}
