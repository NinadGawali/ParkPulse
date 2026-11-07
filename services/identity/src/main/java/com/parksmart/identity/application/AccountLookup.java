package com.parksmart.identity.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.domain.Account;
import java.util.UUID;

final class AccountLookup {

  private AccountLookup() {}

  /** The caller's own account; a valid token for a missing account is treated as 401. */
  static Account require(AccountRepository accounts, UUID accountId) {
    return accounts
        .findById(accountId)
        .orElseThrow(() -> ApiException.unauthorized("Account no longer exists"));
  }
}
