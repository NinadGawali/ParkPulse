package com.parksmart.profile.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.profile.application.port.PayoutAccountRepository;
import com.parksmart.profile.application.port.ProfileRepository;
import com.parksmart.profile.domain.DomainException;
import com.parksmart.profile.domain.PayoutAccount;
import com.parksmart.profile.domain.ProcessorType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A host's payout accounts. The controller checks the HOST role; this class checks ownership. */
@Service
public class PayoutAccountService {

  private final PayoutAccountRepository accounts;
  private final ProfileRepository profiles;
  private final ProfileProperties properties;
  private final Clock clock;

  public PayoutAccountService(
      PayoutAccountRepository accounts,
      ProfileRepository profiles,
      ProfileProperties properties,
      Clock clock) {
    this.accounts = accounts;
    this.profiles = profiles;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<PayoutAccount> list(UUID hostAccountId) {
    return accounts.listByHost(hostAccountId);
  }

  @Transactional
  public PayoutAccount add(UUID hostAccountId, ProcessorType type, String token) {
    if (type == ProcessorType.FAKE && !properties.allowFakeProcessor()) {
      throw new DomainException(
          "processor-not-allowed", "FAKE payout accounts are not accepted here");
    }
    Instant now = clock.instant();
    profiles.lockAccount(hostAccountId, now);
    if (accounts.listByHost(hostAccountId).size() >= PayoutAccount.MAX_PER_HOST) {
      throw new DomainException(
          "payout-limit", "At most " + PayoutAccount.MAX_PER_HOST + " payout accounts");
    }
    PayoutAccount account = PayoutAccount.add(UUID.randomUUID(), hostAccountId, type, token, now);
    accounts.add(account);
    return account;
  }

  @Transactional
  public void delete(UUID hostAccountId, UUID payoutAccountId) {
    PayoutAccount account =
        accounts
            .findById(payoutAccountId)
            .orElseThrow(() -> ApiException.notFound("Payout account"));
    if (!account.isOwnedBy(hostAccountId)) {
      throw ApiException.forbidden("Not your payout account");
    }
    accounts.delete(payoutAccountId);
  }
}
