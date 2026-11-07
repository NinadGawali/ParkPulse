package com.parksmart.identity.application;

import com.parksmart.common.events.identity.RoleGranted;
import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.application.port.EventOutbox;
import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.RoleType;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Operations on the caller's own account (ownership is implicit: the ID comes from the JWT). */
@Service
public class AccountService {

  private final AccountRepository accounts;
  private final EventOutbox outbox;
  private final Clock clock;

  public AccountService(AccountRepository accounts, EventOutbox outbox, Clock clock) {
    this.accounts = accounts;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AccountView me(UUID accountId) {
    return AccountView.of(AccountLookup.require(accounts, accountId));
  }

  @Transactional
  public AccountView becomeHost(UUID accountId) {
    Account account = AccountLookup.require(accounts, accountId);
    if (account.grantHost()) {
      accounts.update(account);
      outbox.publish(new RoleGranted(accountId, RoleType.HOST.name(), clock.instant()));
    }
    return AccountView.of(account);
  }
}
