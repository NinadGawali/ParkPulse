package com.parksmart.identity.application.port;

import com.parksmart.identity.domain.RefreshToken;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {

  void insert(RefreshToken token);

  Optional<RefreshToken> findByHash(String tokenHash);

  /**
   * Atomically revokes {@code tokenId} and links it to its successor, only if it is still active.
   *
   * @return false if another request already rotated or revoked it (treat as reuse)
   */
  boolean rotate(UUID tokenId, UUID replacedByTokenId);

  void revokeFamily(UUID familyId);

  void revokeAllForAccount(UUID accountId);

  /** Revokes a token only if it belongs to {@code accountId}. */
  void revokeOwned(String tokenHash, UUID accountId);
}
