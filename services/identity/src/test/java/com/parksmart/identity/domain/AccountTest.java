package com.parksmart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  private Account newAccount() {
    return Account.register(UUID.randomUUID(), " Host@Example.com ", "hash", "2026-01", NOW);
  }

  @Test
  void new_account_is_pending_driver() {
    Account account = newAccount();
    assertThat(account.status()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
    assertThat(account.roles()).containsExactly(RoleType.DRIVER);
    assertThat(account.email()).isEqualTo("Host@Example.com");
    assertThat(account.canLogIn()).isTrue();
  }

  @Test
  void verifying_email_activates_once() {
    Account account = newAccount();
    assertThat(account.verifyEmail(NOW)).isTrue();
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.verifyEmail(NOW.plusSeconds(5))).isFalse();
    assertThat(account.emailVerifiedAt()).isEqualTo(NOW);
  }

  @Test
  void becoming_host_requires_active_account() {
    Account account = newAccount();
    assertThatThrownBy(account::grantHost)
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo("account-not-active"));

    account.verifyEmail(NOW);
    assertThat(account.grantHost()).isTrue();
    assertThat(account.grantHost()).isFalse();
    assertThat(account.roles()).contains(RoleType.DRIVER, RoleType.HOST);
  }

  @Test
  void five_failed_logins_lock_the_account_for_fifteen_minutes() {
    Account account = newAccount();
    for (int i = 0; i < Account.MAX_FAILED_LOGINS - 1; i++) {
      account.recordFailedLogin(NOW);
    }
    assertThat(account.isLocked(NOW)).isFalse();
    account.recordFailedLogin(NOW);
    assertThat(account.isLocked(NOW)).isTrue();
    assertThat(account.isLocked(NOW.plus(Account.LOCKOUT))).isFalse();
  }

  @Test
  void changing_a_verified_phone_unverifies_it() {
    Account account = newAccount();
    assertThat(account.startPhoneChange("+919876543210")).isFalse();
    account.verifyPhone("+919876543210", NOW);
    assertThat(account.phoneVerifiedAt()).isEqualTo(NOW);

    assertThat(account.startPhoneChange("+919999999999")).isTrue();
    assertThat(account.phone()).isNull();
    assertThat(account.phoneVerifiedAt()).isNull();
  }

  @Test
  void requesting_the_already_verified_phone_is_rejected() {
    Account account = newAccount();
    account.verifyPhone("+919876543210", NOW);
    assertThatThrownBy(() -> account.startPhoneChange("+919876543210"))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void suspended_account_cannot_log_in() {
    Account account =
        new Account(
            UUID.randomUUID(),
            "a@b.c",
            "h",
            AccountStatus.SUSPENDED,
            null,
            NOW,
            null,
            "1",
            0,
            null,
            java.util.Set.of(RoleType.DRIVER),
            NOW,
            3);
    assertThat(account.canLogIn()).isFalse();
  }
}
