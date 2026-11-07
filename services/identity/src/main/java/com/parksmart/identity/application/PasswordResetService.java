package com.parksmart.identity.application;

import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.application.port.PasswordHasher;
import com.parksmart.identity.application.port.RefreshTokenRepository;
import com.parksmart.identity.application.port.Secrets;
import com.parksmart.identity.application.port.VerificationTokenRepository;
import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.DomainException;
import com.parksmart.identity.domain.PasswordPolicy;
import com.parksmart.identity.domain.VerificationPurpose;
import com.parksmart.identity.domain.VerificationToken;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final RefreshTokenRepository refreshTokens;
  private final VerificationCodes verificationCodes;
  private final PasswordHasher passwordHasher;
  private final Secrets secrets;
  private final Clock clock;

  public PasswordResetService(
      AccountRepository accounts,
      VerificationTokenRepository tokens,
      RefreshTokenRepository refreshTokens,
      VerificationCodes verificationCodes,
      PasswordHasher passwordHasher,
      Secrets secrets,
      Clock clock) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.refreshTokens = refreshTokens;
    this.verificationCodes = verificationCodes;
    this.passwordHasher = passwordHasher;
    this.secrets = secrets;
    this.clock = clock;
  }

  /** Always succeeds from the caller's point of view (no account enumeration). */
  @Transactional
  public void forgotPassword(String email) {
    accounts
        .findByEmail(email)
        .filter(Account::canLogIn)
        .ifPresent(account ->
            verificationCodes.issue(
                account.id(), VerificationPurpose.PASSWORD_RESET, account.email()));
  }

  @Transactional
  public void resetPassword(String rawToken, String newPassword) {
    PasswordPolicy.check(newPassword);
    Instant now = clock.instant();
    String hash = secrets.hash(rawToken);
    VerificationToken token =
        tokens
            .findActiveByHashForUpdate(hash, VerificationPurpose.PASSWORD_RESET)
            .orElseThrow(PasswordResetService::invalidToken);
    try {
      token.redeem(hash, now);
    } catch (DomainException e) {
      throw invalidToken();
    }
    Account account =
        accounts.findById(token.accountId()).orElseThrow(PasswordResetService::invalidToken);
    account.changePassword(passwordHasher.hash(newPassword));
    accounts.update(account);
    tokens.update(token);
    refreshTokens.revokeAllForAccount(account.id());
  }

  private static DomainException invalidToken() {
    return new DomainException("invalid-token", "The reset link is invalid or has expired");
  }
}
