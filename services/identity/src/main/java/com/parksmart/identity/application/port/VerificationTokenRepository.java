package com.parksmart.identity.application.port;

import com.parksmart.identity.domain.VerificationPurpose;
import com.parksmart.identity.domain.VerificationToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface VerificationTokenRepository {

  void insert(VerificationToken token);

  /** The newest unconsumed token of this purpose for the account, locked for update. */
  Optional<VerificationToken> findActiveForUpdate(UUID accountId, VerificationPurpose purpose);

  /** Unconsumed token with this hash and purpose (used for password reset links), locked. */
  Optional<VerificationToken> findActiveByHashForUpdate(
      String tokenHash, VerificationPurpose purpose);

  void update(VerificationToken token);

  /** Consumes every unconsumed token of this purpose for the account (on re-issue). */
  void invalidateActive(UUID accountId, VerificationPurpose purpose, Instant now);

  /** How many tokens of this purpose were issued since {@code since}, and when the last one was. */
  IssueStats issueStats(UUID accountId, VerificationPurpose purpose, Instant since);

  record IssueStats(int issuedSince, Instant lastIssuedAt) {}
}
