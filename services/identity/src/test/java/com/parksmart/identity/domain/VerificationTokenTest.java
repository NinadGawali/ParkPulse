package com.parksmart.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerificationTokenTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  private VerificationToken token() {
    return VerificationToken.issue(
        UUID.randomUUID(), UUID.randomUUID(), VerificationPurpose.EMAIL_VERIFY, "right", null, NOW);
  }

  @Test
  void correct_code_consumes_the_token() {
    VerificationToken token = token();
    token.redeem("right", NOW.plusSeconds(60));
    assertThat(token.consumedAt()).isNotNull();
    assertThatThrownBy(() -> token.redeem("right", NOW.plusSeconds(61)))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void expired_token_is_rejected() {
    VerificationToken token = token();
    Instant later = NOW.plus(VerificationPurpose.EMAIL_VERIFY.ttl());
    assertThatThrownBy(() -> token.redeem("right", later))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.code()).isEqualTo("invalid-code"));
  }

  @Test
  void token_is_burned_after_five_wrong_guesses() {
    VerificationToken token = token();
    for (int i = 0; i < VerificationToken.MAX_ATTEMPTS; i++) {
      assertThatThrownBy(() -> token.redeem("wrong", NOW)).isInstanceOf(DomainException.class);
    }
    assertThat(token.attempts()).isEqualTo(VerificationToken.MAX_ATTEMPTS);
    assertThat(token.consumedAt()).isNotNull();
    assertThatThrownBy(() -> token.redeem("right", NOW)).isInstanceOf(DomainException.class);
  }

  @Test
  void password_policy_requires_length_letter_and_digit() {
    assertThatThrownBy(() -> PasswordPolicy.check("short1")).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> PasswordPolicy.check("onlyletterslong"))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> PasswordPolicy.check("1234567890123"))
        .isInstanceOf(DomainException.class);
    PasswordPolicy.check("correct horse 42");
  }
}
