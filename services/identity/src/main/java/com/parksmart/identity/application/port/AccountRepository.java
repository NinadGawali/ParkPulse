package com.parksmart.identity.application.port;

import com.parksmart.identity.domain.Account;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

  Optional<Account> findById(UUID id);

  /** Case-insensitive lookup. */
  Optional<Account> findByEmail(String email);

  /** True if another (non-deleted) account already has this phone verified. */
  boolean isPhoneTakenByAnotherAccount(String phone, UUID accountId);

  /**
   * @throws com.parksmart.identity.domain.DomainException {@code email-taken} on duplicates
   */
  void insert(Account account);

  /**
   * Saves changes guarded by the version the aggregate was loaded with.
   *
   * @throws com.parksmart.identity.domain.DomainException {@code concurrent-modification} if the
   *     row changed meanwhile, {@code phone-in-use} on a phone uniqueness race
   */
  void update(Account account);
}
