package com.parksmart.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CursorTest {

  @Test
  void round_trips() {
    Cursor cursor = new Cursor(Instant.parse("2026-11-07T10:00:00.123Z"), UUID.randomUUID());
    assertThat(Cursor.decode(cursor.encode())).isEqualTo(cursor);
  }

  @Test
  void blank_means_first_page() {
    assertThat(Cursor.decode(null)).isNull();
    assertThat(Cursor.decode(" ")).isNull();
  }

  @Test
  void garbage_is_a_400() {
    assertThatThrownBy(() -> Cursor.decode("not-a-cursor"))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void limit_is_clamped() {
    assertThat(Cursor.limit(null)).isEqualTo(20);
    assertThat(Cursor.limit(0)).isEqualTo(1);
    assertThat(Cursor.limit(500)).isEqualTo(100);
    assertThat(Cursor.limit(50)).isEqualTo(50);
  }
}
