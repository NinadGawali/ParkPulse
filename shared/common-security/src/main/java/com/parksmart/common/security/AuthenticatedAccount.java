package com.parksmart.common.security;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** The caller, as established from a verified access token. */
public record AuthenticatedAccount(UUID accountId, Set<Role> roles, String accessToken) {

  public AuthenticatedAccount {
    roles = roles.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(roles));
  }

  public boolean hasRole(Role role) {
    return roles.contains(role);
  }

  public boolean isAdmin() {
    return hasRole(Role.ADMIN);
  }

  /** True when the caller owns the resource or is an admin. */
  public boolean ownsOrAdmin(UUID ownerAccountId) {
    return accountId.equals(ownerAccountId) || isAdmin();
  }

  @Override
  public String toString() {
    // Never print the token.
    return "AuthenticatedAccount[accountId=" + accountId + ", roles=" + roles + "]";
  }
}
