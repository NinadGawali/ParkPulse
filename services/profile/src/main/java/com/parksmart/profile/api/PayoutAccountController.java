package com.parksmart.profile.api;

import com.parksmart.common.security.CurrentAccount;
import com.parksmart.common.security.Role;
import com.parksmart.profile.api.dto.Requests;
import com.parksmart.profile.api.dto.Responses.PayoutAccountList;
import com.parksmart.profile.api.dto.Responses.PayoutAccountResponse;
import com.parksmart.profile.application.PayoutAccountService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HOST role required for every call; ownership of a single account is checked in the service. */
@RestController
@RequestMapping("/api/v1/profiles/me/payout-accounts")
class PayoutAccountController {

  private final PayoutAccountService accounts;

  PayoutAccountController(PayoutAccountService accounts) {
    this.accounts = accounts;
  }

  @GetMapping
  PayoutAccountList list() {
    return new PayoutAccountList(
        accounts.list(hostId()).stream().map(PayoutAccountResponse::of).toList());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  PayoutAccountResponse add(@Valid @RequestBody Requests.AddPayoutAccount body) {
    return PayoutAccountResponse.of(
        accounts.add(hostId(), body.processorType(), body.processorToken()));
  }

  @DeleteMapping("/{payoutAccountId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable UUID payoutAccountId) {
    accounts.delete(hostId(), payoutAccountId);
  }

  private static UUID hostId() {
    return CurrentAccount.requireRole(Role.HOST).accountId();
  }
}
