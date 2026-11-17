package com.parksmart.identity.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.identity.api.dto.Responses.AccountSummary;
import com.parksmart.identity.application.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/me")
class AccountController {

  private final AccountService accounts;

  AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  AccountSummary me() {
    return AccountSummary.of(accounts.me(CurrentAccount.require().accountId()));
  }

  @PostMapping("/roles/host")
  AccountSummary becomeHost() {
    return AccountSummary.of(accounts.becomeHost(CurrentAccount.require().accountId()));
  }
}
