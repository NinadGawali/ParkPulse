package com.parksmart.identity.infrastructure.persistence;

import static com.parksmart.identity.infrastructure.persistence.SqlTime.instant;
import static com.parksmart.identity.infrastructure.persistence.SqlTime.ts;

import com.parksmart.identity.application.port.VerificationTokenRepository;
import com.parksmart.identity.domain.VerificationPurpose;
import com.parksmart.identity.domain.VerificationToken;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcVerificationTokenRepository implements VerificationTokenRepository {

  private static final String SELECT =
      """
      SELECT id, account_id, purpose, token_hash, destination, expires_at, attempts, consumed_at
      FROM verification_tokens
      """;

  private final JdbcClient jdbc;

  JdbcVerificationTokenRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(VerificationToken token) {
    jdbc.sql(
            """
            INSERT INTO verification_tokens
              (id, account_id, purpose, token_hash, destination, expires_at, attempts, consumed_at)
            VALUES (:id, :accountId, :purpose, :hash, :destination, :expiresAt, :attempts, :consumedAt)
            """)
        .param("id", token.id())
        .param("accountId", token.accountId())
        .param("purpose", token.purpose().name())
        .param("hash", token.tokenHash())
        .param("destination", token.destination())
        .param("expiresAt", ts(token.expiresAt()))
        .param("attempts", token.attempts())
        .param("consumedAt", ts(token.consumedAt()))
        .update();
  }

  @Override
  public Optional<VerificationToken> findActiveForUpdate(
      UUID accountId, VerificationPurpose purpose) {
    return jdbc.sql(
            SELECT
                + """
                WHERE account_id = :accountId AND purpose = :purpose AND consumed_at IS NULL
                ORDER BY created_at DESC LIMIT 1 FOR UPDATE
                """)
        .param("accountId", accountId)
        .param("purpose", purpose.name())
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<VerificationToken> findActiveByHashForUpdate(
      String tokenHash, VerificationPurpose purpose) {
    return jdbc.sql(
            SELECT
                + """
                WHERE token_hash = :hash AND purpose = :purpose AND consumed_at IS NULL
                FOR UPDATE
                """)
        .param("hash", tokenHash)
        .param("purpose", purpose.name())
        .query(this::map)
        .optional();
  }

  @Override
  public void update(VerificationToken token) {
    jdbc.sql(
            "UPDATE verification_tokens SET attempts = :attempts, consumed_at = :consumedAt WHERE id = :id")
        .param("attempts", token.attempts())
        .param("consumedAt", ts(token.consumedAt()))
        .param("id", token.id())
        .update();
  }

  @Override
  public void invalidateActive(UUID accountId, VerificationPurpose purpose, Instant now) {
    jdbc.sql(
            """
            UPDATE verification_tokens SET consumed_at = :now
            WHERE account_id = :accountId AND purpose = :purpose AND consumed_at IS NULL
            """)
        .param("now", ts(now))
        .param("accountId", accountId)
        .param("purpose", purpose.name())
        .update();
  }

  private VerificationToken map(ResultSet rs, int row) throws SQLException {
    return new VerificationToken(
        rs.getObject("id", UUID.class),
        rs.getObject("account_id", UUID.class),
        VerificationPurpose.valueOf(rs.getString("purpose")),
        rs.getString("token_hash"),
        rs.getString("destination"),
        instant(rs, "expires_at"),
        rs.getInt("attempts"),
        instant(rs, "consumed_at"));
  }
}
