package com.parksmart.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileTest {

  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");

  private final Profile profile = Profile.createEmpty(UUID.randomUUID(), T0);

  @Test
  void new_profile_is_empty() {
    assertThat(profile.isNew()).isTrue();
    assertThat(profile.hasPhoto()).isFalse();
    assertThat(profile.rating().count()).isZero();
    assertThat(profile.rating().average()).isNull();
  }

  @Test
  void update_trims_and_reports_public_changes_only() {
    assertThat(profile.updateDetails("  Asha ", " bio ", "  ", T0.plusSeconds(1))).isTrue();
    assertThat(profile.displayName()).isEqualTo("Asha");
    assertThat(profile.bio()).isEqualTo("bio");
    assertThat(profile.city()).isNull();
    assertThat(profile.updatedAt()).isEqualTo(T0.plusSeconds(1));

    assertThat(profile.updateDetails("Asha", "other", "Pune", T0.plusSeconds(2))).isFalse();
    assertThat(profile.city()).isEqualTo("Pune");
  }

  @Test
  void display_name_rules() {
    assertThatThrownBy(() -> profile.updateDetails(" ", null, null, T0))
        .isInstanceOf(DomainException.class)
        .extracting("code")
        .isEqualTo("invalid-displayName");
    assertThatThrownBy(() -> profile.updateDetails("a", null, null, T0))
        .extracting("code")
        .isEqualTo("invalid-displayName");
    assertThatThrownBy(() -> profile.updateDetails("x".repeat(61), null, null, T0))
        .extracting("code")
        .isEqualTo("invalid-displayName");
    assertThatThrownBy(() -> profile.updateDetails("Bad\tName", null, null, T0))
        .extracting("code")
        .isEqualTo("invalid-displayName");
  }

  @Test
  void bio_may_contain_newlines_but_not_other_control_characters() {
    profile.updateDetails("Asha", "line one\nline two", null, T0);
    assertThat(profile.bio()).contains("\n");
    assertThatThrownBy(() -> profile.updateDetails("Asha", "bell\u0007", null, T0))
        .extracting("code")
        .isEqualTo("invalid-bio");
  }

  @Test
  void replacing_a_photo_returns_the_previous_one() {
    ProfilePhoto first = new ProfilePhoto("profiles/a/1", "https://cdn/1");
    ProfilePhoto second = new ProfilePhoto("profiles/a/2", "https://cdn/2");
    assertThat(profile.replacePhoto(first, T0)).isEmpty();
    assertThat(profile.replacePhoto(second, T0)).contains(first);
    assertThat(profile.removePhoto(T0)).contains(second);
    assertThat(profile.removePhoto(T0)).isEmpty();
    assertThat(profile.hasPhoto()).isFalse();
  }

  @Test
  void older_ratings_never_overwrite_newer_ones() {
    assertThat(profile.applyRating(new BigDecimal("4.333"), 3, T0.plusSeconds(10))).isTrue();
    assertThat(profile.rating().average()).isEqualByComparingTo("4.33");
    assertThat(profile.applyRating(new BigDecimal("1.0"), 1, T0.plusSeconds(5))).isFalse();
    assertThat(profile.applyRating(new BigDecimal("1.0"), 1, T0.plusSeconds(10))).isFalse();
    assertThat(profile.rating().count()).isEqualTo(3);
    assertThat(profile.applyRating(new BigDecimal("4.5"), 4, T0.plusSeconds(11))).isTrue();
    assertThat(profile.rating().average()).isEqualByComparingTo("4.50");
  }
}
