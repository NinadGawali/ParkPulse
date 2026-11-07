package com.parksmart.identity.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.identity.application.port.AccessTokenIssuer;
import com.parksmart.identity.application.port.AccessTokenIssuer.IssuedAccessToken;
import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.application.port.PasswordHasher;
import com.parksmart.identity.application.port.RefreshTokenRepository;
import com.parksmart.identity.application.port.Secrets;
import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.RefreshToken;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Login, refresh-token rotation, logout. Failures are committed (failed-attempt counters, family
 * revocation) before the error is returned, hence {@code noRollbackFor}.
 */
@Service
public class AuthService {

  private final AccountRepository accounts;
  private final RefreshTokenRepository refreshTokens;
  private final PasswordHasher passwordHasher;
  private final AccessTokenIssuer accessTokenIssuer;
  private final Secrets secrets;
  private final IdentityProperties properties;
  private final Clock clock;

  public AuthService(
      AccountRepository accounts,
      RefreshTokenRepository refreshTokens,
      PasswordHasher passwordHasher,
      AccessTokenIssuer accessTokenIssuer,
      Secrets secrets,
      IdentityProperties properties,
      Clock clock) {
    this.accounts = accounts;
    this.refreshTokens = refreshTokens;
    this.passwordHasher = passwordHasher;
    this.accessTokenIssuer = accessTokenIssuer;
    this.secrets = secrets;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional(noRollbackFor = ApiException.class)
  public Tokens login(String email, String password) {
    Instant now = clock.instant();
    Optional<Account> found = accounts.findByEmail(email);
    if (found.isEmpty()) {
      passwordHasher.simulateCheck(password);
      throw invalidCredentials();
    }
    Account account = found.get();
    if (account.isLocked(now)) {
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "account-locked",
          "Account temporarily locked",
          "Too many failed attempts; try again later");
    }
    if (!passwordHasher.matches(password, account.passwordHash())) {
      account.recordFailedLogin(now);
      accounts.update(account);
      throw invalidCredentials();
    }
    if (!account.canLogIn()) {
      throw accountDisabled();
    }
    if (account.failedLoginAttempts() > 0 || account.lockedUntil() != null) {
      account.recordSuccessfulLogin();
      accounts.update(account);
    }
    return issue(account, UUID.randomUUID(), now, null);
  }

  @Transactional(noRollbackFor = ApiException.class)
  public Tokens refresh(String rawRefreshToken) {
    Instant now = clock.instant();
    RefreshToken current =
        refreshTokens.findByHash(secrets.hash(rawRefreshToken)).orElseThrow(this::invalidRefresh);
    if (current.revoked()) {
      // Reuse of a rotated token: assume theft, kill the whole login.
      refreshTokens.revokeFamily(current.familyId());
      throw invalidRefresh();
    }
    if (current.isExpired(now)) {
      throw invalidRefresh();
    }
    Account account = accounts.findById(current.accountId()).orElseThrow(this::invalidRefresh);
    if (!account.canLogIn()) {
      refreshTokens.revokeFamily(current.familyId());
      throw accountDisabled();
    }
    return issue(account, current.familyId(), now, current);
  }

  @Transactional
  public void logout(UUID accountId, String rawRefreshToken) {
    refreshTokens.revokeOwned(secrets.hash(rawRefreshToken), accountId);
  }

  private Tokens issue(Account account, UUID familyId, Instant now, RefreshToken previous) {
    String rawRefresh = secrets.newOpaqueToken();
    RefreshToken next =
        RefreshToken.issue(
            UUID.randomUUID(),
            account.id(),
            familyId,
            secrets.hash(rawRefresh),
            now,
            properties.refreshTokenTtl());
    refreshTokens.insert(next);
    if (previous != null && !refreshTokens.rotate(previous.id(), next.id())) {
      // A concurrent refresh already used this token.
      refreshTokens.revokeFamily(familyId);
      throw invalidRefresh();
    }
    IssuedAccessToken access = accessTokenIssuer.issue(account);
    return new Tokens(access.value(), rawRefresh, access.expiresInSeconds());
  }

  private ApiException invalidCredentials() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "invalid-credentials",
        "Invalid credentials",
        "Email or password is incorrect");
  }

  private ApiException invalidRefresh() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED,
        "invalid-refresh-token",
        "Invalid refresh token",
        "The refresh token is invalid, expired, or already used");
  }

  private ApiException accountDisabled() {
    return new ApiException(
        HttpStatus.FORBIDDEN, "account-disabled", "Account disabled", "This account cannot log in");
  }
}
