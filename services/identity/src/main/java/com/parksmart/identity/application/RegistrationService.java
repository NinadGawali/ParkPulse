package com.parksmart.identity.application;

import com.parksmart.common.events.identity.AccountRegistered;
import com.parksmart.common.events.identity.ContactDetailsChanged;
import com.parksmart.common.events.identity.RoleGranted;
import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.application.port.EventOutbox;
import com.parksmart.identity.application.port.PasswordHasher;
import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.PasswordPolicy;
import com.parksmart.identity.domain.RoleType;
import com.parksmart.identity.domain.VerificationPurpose;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {

  private final AccountRepository accounts;
  private final PasswordHasher passwordHasher;
  private final VerificationCodes verificationCodes;
  private final EventOutbox outbox;
  private final Clock clock;

  public RegistrationService(
      AccountRepository accounts,
      PasswordHasher passwordHasher,
      VerificationCodes verificationCodes,
      EventOutbox outbox,
      Clock clock) {
    this.accounts = accounts;
    this.passwordHasher = passwordHasher;
    this.verificationCodes = verificationCodes;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public AccountView register(String email, String password, String acceptedTermsVersion) {
    PasswordPolicy.check(password);
    Instant now = clock.instant();
    Account account =
        Account.register(
            UUID.randomUUID(), email, passwordHasher.hash(password), acceptedTermsVersion, now);
    accounts.insert(account);

    outbox.publish(new AccountRegistered(account.id(), now));
    outbox.publish(new RoleGranted(account.id(), RoleType.DRIVER.name(), now));
    outbox.publish(new ContactDetailsChanged(account.id(), account.email(), null, now));
    verificationCodes.issue(account.id(), VerificationPurpose.EMAIL_VERIFY, account.email());
    return AccountView.of(account);
  }
}
