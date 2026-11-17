package com.parksmart.search.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parksmart.search.domain.Availability.Rule;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityTest {

  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Test
  void rules_are_checked_in_the_listing_time_zone() {
    List<Rule> mondays = List.of(new Rule(DayOfWeek.MONDAY, 8 * 60, 20 * 60));
    // 2030-01-07 10:00-12:00 IST = 04:30-06:30Z
    assertThat(
            Availability.covers(
                mondays,
                IST,
                Instant.parse("2030-01-07T04:30:00Z"),
                Instant.parse("2030-01-07T06:30:00Z")))
        .isTrue();
    assertThat(
            Availability.covers(
                mondays,
                IST,
                Instant.parse("2030-01-07T01:30:00Z"),
                Instant.parse("2030-01-07T03:00:00Z")))
        .isFalse();
  }

  @Test
  void overnight_and_adjacent_rules_join() {
    List<Rule> rules =
        List.of(
            new Rule(DayOfWeek.MONDAY, 18 * 60, 1440),
            new Rule(DayOfWeek.TUESDAY, 0, 6 * 60),
            new Rule(DayOfWeek.TUESDAY, 6 * 60, 9 * 60));
    assertThat(
            Availability.covers(
                rules,
                ZoneId.of("UTC"),
                Instant.parse("2030-01-07T20:00:00Z"),
                Instant.parse("2030-01-08T08:00:00Z")))
        .isTrue();
    assertThat(
            Availability.covers(
                rules,
                ZoneId.of("UTC"),
                Instant.parse("2030-01-07T20:00:00Z"),
                Instant.parse("2030-01-08T10:00:00Z")))
        .isFalse();
  }

  @Test
  void query_validation() {
    assertThatThrownBy(() ->
            new SearchQuery(
                0, 0, 51, null, null, null, null, null, null, SearchQuery.Sort.DISTANCE, 20))
        .extracting("code")
        .isEqualTo("invalid-search");
    Instant s = Instant.parse("2030-01-01T00:00:00Z");
    assertThatThrownBy(() ->
            new SearchQuery(
                0,
                0,
                5,
                s,
                s.plusSeconds(32L * 86400),
                null,
                null,
                null,
                null,
                SearchQuery.Sort.DISTANCE,
                20))
        .isInstanceOf(DomainException.class);
    assertThat(SearchQuery.vehicleRank("SEDAN")).isEqualTo(2);
  }
}
