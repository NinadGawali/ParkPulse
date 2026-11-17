package com.parksmart.identity.infrastructure.persistence;

import static com.parksmart.identity.infrastructure.persistence.SqlTime.instant;
import static com.parksmart.identity.infrastructure.persistence.SqlTime.ts;

import com.parksmart.identity.application.port.AccountRepository;
import com.parksmart.identity.domain.Account;
import com.parksmart.identity.domain.AccountStatus;
import com.parksmart.identity.domain.DomainException;
import com.parksmart.identity.domain.RoleType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

  private static final String SELECT =
      """
      SELECT id, email, password_hash, status, phone, email_verified_at, phone_verified_at,
             accepted_terms_version, failed_login_attempts, locked_until, created_at, version
      FROM accounts
      """;

  private final JdbcClient jdbc;

  JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Account> findById(UUID id) {
    return find(SELECT + " WHERE id = :value AND deleted_at IS NULL", id);
  }

  @Override
  public Optional<Account> findByEmail(String email) {
    return find(
        SELECT + " WHERE email_normalized = LOWER(:value) AND deleted_at IS NULL", email.trim());
  }

  @Override
  public boolean isPhoneTakenByAnotherAccount(String phone, UUID accountId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM accounts
                           WHERE phone = :phone AND id <> :id AND deleted_at IS NULL)
            """)
        .param("phone", phone)
        .param("id", accountId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public void insert(Account account) {
    try {
      jdbc.sql(
              """
              INSERT INTO accounts (id, email, password_hash, status, phone, email_verified_at,
                                    phone_verified_at, accepted_terms_version, created_at, updated_at)
              VALUES (:id, :email, :passwordHash, :status, :phone, :emailVerifiedAt,
                      :phoneVerifiedAt, :terms, :createdAt, :createdAt)
              """)
          .param("id", account.id())
          .param("email", account.email())
          .param("passwordHash", account.passwordHash())
          .param("status", account.status().name())
          .param("phone", account.phone())
          .param("emailVerifiedAt", ts(account.emailVerifiedAt()))
          .param("phoneVerifiedAt", ts(account.phoneVerifiedAt()))
          .param("terms", account.acceptedTermsVersion())
          .param("createdAt", ts(account.createdAt()))
          .update();
    } catch (DuplicateKeyException e) {
      throw new DomainException("email-taken", "An account with this email already exists");
    }
    insertMissingRoles(account);
  }

  @Override
  public void update(Account account) {
    int updated;
    try {
      updated =
          jdbc.sql(
                  """
                  UPDATE accounts
                  SET password_hash = :passwordHash, status = :status, phone = :phone,
                      email_verified_at = :emailVerifiedAt, phone_verified_at = :phoneVerifiedAt,
                      updated_at = now(), version = version + 1
                  WHERE id = :id AND version = :version
                  """)
              .param("passwordHash", account.passwordHash())
              .param("status", account.status().name())
              .param("phone", account.phone())
              .param("emailVerifiedAt", ts(account.emailVerifiedAt()))
              .param("phoneVerifiedAt", ts(account.phoneVerifiedAt()))
              .param("id", account.id())
              .param("version", account.version())
              .update();
    } catch (DuplicateKeyException e) {
      throw new DomainException("phone-in-use", "This phone is verified on another account");
    }
    if (updated == 0) {
      throw new DomainException(
          "concurrent-modification", "The account was changed by another request; retry");
    }
    insertMissingRoles(account);
  }

  @Override
  public void recordFailedLogin(UUID accountId, Instant now) {
    jdbc.sql(
            """
            UPDATE accounts SET
              locked_until = CASE WHEN failed_login_attempts + 1 >= :max
                                  THEN :lockUntil ELSE locked_until END,
              failed_login_attempts = CASE WHEN failed_login_attempts + 1 >= :max
                                           THEN 0 ELSE failed_login_attempts + 1 END
            WHERE id = :id
            """)
        .param("max", Account.MAX_FAILED_LOGINS)
        .param("lockUntil", ts(now.plus(Account.LOCKOUT)))
        .param("id", accountId)
        .update();
  }

  @Override
  public void resetLoginFailures(UUID accountId) {
    jdbc.sql("UPDATE accounts SET failed_login_attempts = 0, locked_until = NULL WHERE id = :id")
        .param("id", accountId)
        .update();
  }

  private void insertMissingRoles(Account account) {
    for (RoleType role : account.roles()) {
      jdbc.sql(
              """
              INSERT INTO roles (account_id, role_type) VALUES (:id, :role)
              ON CONFLICT DO NOTHING
              """)
          .param("id", account.id())
          .param("role", role.name())
          .update();
    }
  }

  private Optional<Account> find(String sql, Object value) {
    return jdbc.sql(sql)
        .param("value", value)
        .query((rs, row) -> {
          UUID id = rs.getObject("id", UUID.class);
          return new Account(
              id,
              rs.getString("email"),
              rs.getString("password_hash"),
              AccountStatus.valueOf(rs.getString("status")),
              rs.getString("phone"),
              instant(rs, "email_verified_at"),
              instant(rs, "phone_verified_at"),
              rs.getString("accepted_terms_version"),
              rs.getInt("failed_login_attempts"),
              instant(rs, "locked_until"),
              roles(id),
              instant(rs, "created_at"),
              rs.getInt("version"));
        })
        .optional();
  }

  private Set<RoleType> roles(UUID accountId) {
    List<RoleType> roles =
        jdbc.sql("SELECT role_type FROM roles WHERE account_id = :id")
            .param("id", accountId)
            .query((rs, row) -> RoleType.valueOf(rs.getString(1)))
            .list();
    return roles.isEmpty() ? EnumSet.noneOf(RoleType.class) : EnumSet.copyOf(roles);
  }
}
