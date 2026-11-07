package com.parksmart.identity.infrastructure.persistence;

import static com.parksmart.identity.infrastructure.persistence.SqlTime.instant;
import static com.parksmart.identity.infrastructure.persistence.SqlTime.ts;

import com.parksmart.identity.application.port.RefreshTokenRepository;
import com.parksmart.identity.domain.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRefreshTokenRepository implements RefreshTokenRepository {

  private final JdbcClient jdbc;

  JdbcRefreshTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(RefreshToken token) {
    jdbc.sql(
            """
            INSERT INTO refresh_tokens (id, account_id, family_id, family_started_at, token_hash,
                                        issued_at, expires_at)
            VALUES (:id, :accountId, :familyId, :familyStartedAt, :hash, :issuedAt, :expiresAt)
            """)
        .param("id", token.id())
        .param("accountId", token.accountId())
        .param("familyId", token.familyId())
        .param("familyStartedAt", ts(token.familyStartedAt()))
        .param("hash", token.tokenHash())
        .param("issuedAt", ts(token.issuedAt()))
        .param("expiresAt", ts(token.expiresAt()))
        .update();
  }

  @Override
  public Optional<RefreshToken> findByHash(String tokenHash) {
    return jdbc.sql(
            """
            SELECT id, account_id, family_id, family_started_at, token_hash, issued_at, expires_at, revoked,
                   replaced_by_token_id
            FROM refresh_tokens WHERE token_hash = :hash
            """)
        .param("hash", tokenHash)
        .query((rs, row) ->
            new RefreshToken(
                rs.getObject("id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("family_id", UUID.class),
                instant(rs, "family_started_at"),
                rs.getString("token_hash"),
                instant(rs, "issued_at"),
                instant(rs, "expires_at"),
                rs.getBoolean("revoked"),
                rs.getObject("replaced_by_token_id", UUID.class)))
        .optional();
  }

  @Override
  public boolean rotate(UUID tokenId, UUID replacedByTokenId) {
    return jdbc.sql(
                """
                UPDATE refresh_tokens SET revoked = true, replaced_by_token_id = :next
                WHERE id = :id AND revoked = false
                """)
            .param("next", replacedByTokenId)
            .param("id", tokenId)
            .update()
        == 1;
  }

  @Override
  public void revokeFamily(UUID familyId) {
    jdbc.sql(
            "UPDATE refresh_tokens SET revoked = true WHERE family_id = :family AND revoked = false")
        .param("family", familyId)
        .update();
  }

  @Override
  public void revokeAllForAccount(UUID accountId) {
    jdbc.sql(
            "UPDATE refresh_tokens SET revoked = true WHERE account_id = :account AND revoked = false")
        .param("account", accountId)
        .update();
  }

  @Override
  public void revokeOwned(String tokenHash, UUID accountId) {
    jdbc.sql(
            """
            UPDATE refresh_tokens SET revoked = true
            WHERE token_hash = :hash AND account_id = :account AND revoked = false
            """)
        .param("hash", tokenHash)
        .param("account", accountId)
        .update();
  }
}
