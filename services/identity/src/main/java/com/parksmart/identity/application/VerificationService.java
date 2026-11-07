package com.parksmart.identity.application;

import com.parksmart.common.events.identity.ContactDetailsChanged;
import com.parksmart.common.events.identity.EmailVerified;
import com.parksmart.common.events.identity.PhoneUnverified;
import com.parksmart.common.events.identity.PhoneVerified;
import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.application.port.EventOutbox;
import com.parksmart.identity.application.port.Secrets;
import com.parksmart.identity.application.port.VerificationTokenRepository;
import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.DomainException;
import com.parksmart.identity.domain.VerificationPurpose;
import com.parksmart.identity.domain.VerificationToken;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Email and phone verification. Wrong-code attempts are committed (noRollbackFor). */
@Service
public class VerificationService {

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;
  private final VerificationCodes verificationCodes;
  private final Secrets secrets;
  private final EventOutbox outbox;
  private final Clock clock;

  public VerificationService(
      AccountRepository accounts,
      VerificationTokenRepository tokens,
      VerificationCodes verificationCodes,
      Secrets secrets,
      EventOutbox outbox,
      Clock clock) {
    this.accounts = accounts;
    this.tokens = tokens;
    this.verificationCodes = verificationCodes;
    this.secrets = secrets;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public void requestEmailVerification(UUID accountId) {
    Account account = AccountLookup.require(accounts, accountId);
    if (account.emailVerifiedAt() != null) {
      throw new DomainException("email-already-verified", "Email is already verified");
    }
    verificationCodes.issue(accountId, VerificationPurpose.EMAIL_VERIFY, account.email());
  }

  @Transactional(noRollbackFor = DomainException.class)
  public void verifyEmail(UUID accountId, String code) {
    Instant now = clock.instant();
    redeem(accountId, VerificationPurpose.EMAIL_VERIFY, code, now);
    Account account = AccountLookup.require(accounts, accountId);
    if (account.verifyEmail(now)) {
      accounts.update(account);
      outbox.publish(new EmailVerified(accountId, now));
    }
  }

  @Transactional
  public void requestPhoneVerification(UUID accountId, String phone) {
    Instant now = clock.instant();
    Account account = AccountLookup.require(accounts, accountId);
    if (accounts.isPhoneTakenByAnotherAccount(phone, accountId)) {
      throw phoneInUse();
    }
    if (account.startPhoneChange(phone)) {
      accounts.update(account);
      outbox.publish(new PhoneUnverified(accountId, now));
      outbox.publish(new ContactDetailsChanged(accountId, account.email(), null, now));
    }
    verificationCodes.issue(accountId, VerificationPurpose.PHONE_VERIFY, phone);
  }

  @Transactional(noRollbackFor = DomainException.class)
  public void verifyPhone(UUID accountId, String code) {
    Instant now = clock.instant();
    VerificationToken token = redeem(accountId, VerificationPurpose.PHONE_VERIFY, code, now);
    String phone = token.destination();
    if (accounts.isPhoneTakenByAnotherAccount(phone, accountId)) {
      throw phoneInUse();
    }
    Account account = AccountLookup.require(accounts, accountId);
    account.verifyPhone(phone, now);
    accounts.update(account);
    outbox.publish(new PhoneVerified(accountId, now));
    outbox.publish(new ContactDetailsChanged(accountId, account.email(), phone, now));
  }

  private VerificationToken redeem(
      UUID accountId, VerificationPurpose purpose, String code, Instant now) {
    VerificationToken token =
        tokens
            .findActiveForUpdate(accountId, purpose)
            .orElseThrow(
                () -> new DomainException("invalid-code", "The code is invalid or has expired"));
    try {
      token.redeem(secrets.hash(code), now);
    } finally {
      tokens.update(token);
    }
    return token;
  }

  private static DomainException phoneInUse() {
    return new DomainException("phone-in-use", "This phone is verified on another account");
  }
}
