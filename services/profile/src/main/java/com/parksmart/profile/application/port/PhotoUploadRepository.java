package com.parksmart.profile.application.port;

import com.parksmart.profile.domain.PhotoUpload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhotoUploadRepository {

  /** Locks the row for the rest of the transaction (two confirms of one upload serialize). */
  Optional<PhotoUpload> findForUpdate(UUID id);

  void save(PhotoUpload upload);

  /** Unconfirmed uploads of an account that have not expired yet. */
  long countPending(UUID accountId, Instant now);

  /** Upload slots (confirmed or not) whose URL expired before {@code cutoff}, oldest first. */
  List<PhotoUpload> findExpired(Instant cutoff, int limit);

  void delete(UUID id);
}
