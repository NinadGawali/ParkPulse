package com.parksmart.common.security;

import com.parksmart.common.web.ApiException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads the authenticated caller from the security context. */
public final class CurrentAccount {

  private CurrentAccount() {}

  /** The caller; 401 problem if the request is anonymous. */
  public static AuthenticatedAccount require() {
    return find().orElseThrow(() -> ApiException.unauthorized("A valid access token is required"));
  }

  /** The caller, or empty for anonymous requests (public endpoints). */
  public static Optional<AuthenticatedAccount> find() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()) {
      return Optional.of(fromJwt(token.getToken()));
    }
    return Optional.empty();
  }

  /** Requires a role; 403 problem otherwise. */
  public static AuthenticatedAccount requireRole(Role role) {
    AuthenticatedAccount account = require();
    if (!account.hasRole(role)) {
      throw ApiException.forbidden("Requires role " + role);
    }
    return account;
  }

  static AuthenticatedAccount fromJwt(Jwt jwt) {
    return new AuthenticatedAccount(
        UUID.fromString(jwt.getSubject()), roles(jwt), jwt.getTokenValue());
  }

  static Set<Role> roles(Jwt jwt) {
    List<String> names = jwt.getClaimAsStringList(JwtClaims.ROLES);
    Set<Role> roles = EnumSet.noneOf(Role.class);
    if (names != null) {
      for (String name : names) {
        try {
          roles.add(Role.valueOf(name));
        } catch (IllegalArgumentException ignored) {
          // Unknown roles are ignored rather than trusted.
        }
      }
    }
    return roles;
  }
}
