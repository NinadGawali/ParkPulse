package com.parksmart.profile.application.port;

import com.parksmart.profile.domain.PayoutAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayoutAccountRepository {

  Optional<PayoutAccount> findById(UUID id);

  /** Newest first. */
  List<PayoutAccount> listByHost(UUID hostAccountId);

  /**
   * Inserts and flushes; a token already registered throws DomainException("payout-registered").
   */
  void add(PayoutAccount account);

  void delete(UUID id);
}
