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
   * Atomically counts a failed login and, at {@code Account.MAX_FAILED_LOGINS}, locks the account
   * until {@code now + Account.LOCKOUT}. Not version-guarded, so parallel guesses all count.
   */
  void recordFailedLogin(UUID accountId, java.time.Instant now);

  /** Clears the failed-login counter and lock (successful login, password reset). */
  void resetLoginFailures(UUID accountId);

  /**
   * Saves changes guarded by the version the aggregate was loaded with. Login counters are not
   * written here (see {@link #recordFailedLogin}).
   *
   * @throws com.parksmart.identity.domain.DomainException {@code concurrent-modification} if the
   *     row changed meanwhile, {@code phone-in-use} on a phone uniqueness race
   */
  void update(Account account);
}
