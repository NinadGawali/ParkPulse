package com.parksmart.profile.application.port;

import com.parksmart.profile.domain.Profile;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository {

  Optional<Profile> findById(UUID accountId);

  /** Inserts a new profile or updates an existing one (optimistic lock on version). */
  void save(Profile profile);

  /** Creates an empty profile unless one exists. Safe under concurrent calls. */
  void createIfAbsent(UUID accountId, Instant now);

  /**
   * Creates the profile if needed and locks its row until the transaction ends. Serializes
   * per-account limit checks (vehicles, payout accounts, pending uploads) and primary switches.
   */
  void lockAccount(UUID accountId, Instant now);
}
