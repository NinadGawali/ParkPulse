package com.parksmart.search.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

/**
 * Whether a listing's weekly rules cover a window, in the listing's time zone. Same rules as
 * Listing's booking quote: adjacent rules join (also across midnight), start floors and end ceils
 * to the minute, a DST fall-back window covers the repeated wall-clock span.
 */
public final class Availability {

  /** {@code [startMinute, endMinute)} after local midnight; end 1440 = 24:00. */
  public record Rule(DayOfWeek day, int startMinute, int endMinute) {}

  private Availability() {}

  public static boolean covers(List<Rule> rules, ZoneId zone, Instant start, Instant end) {
    List<Rule> sorted =
        rules.stream()
            .sorted(Comparator.comparing(Rule::day).thenComparingInt(Rule::startMinute))
            .toList();
    LocalDateTime from = LocalDateTime.ofInstant(start, zone);
    LocalDateTime to = LocalDateTime.ofInstant(end, zone);
    if (!to.isAfter(from)) {
      LocalDateTime swap = from;
      from = to;
      to = swap.plusMinutes(1);
    }
    for (LocalDate day = from.toLocalDate();
        !day.isAfter(to.toLocalDate());
        day = day.plusDays(1)) {
      int segStart = day.equals(from.toLocalDate()) ? floor(from) : 0;
      int segEnd = day.equals(to.toLocalDate()) ? ceil(to) : 1440;
      if (segStart < segEnd && !dayCovers(sorted, day.getDayOfWeek(), segStart, segEnd)) {
        return false;
      }
    }
    return true;
  }

  private static boolean dayCovers(List<Rule> rules, DayOfWeek day, int from, int to) {
    int reached = from;
    for (Rule rule : rules) {
      if (rule.day() != day) {
        continue;
      }
      if (rule.startMinute() > reached) {
        return false;
      }
      reached = Math.max(reached, rule.endMinute());
      if (reached >= to) {
        return true;
      }
    }
    return reached >= to;
  }

  private static int floor(LocalDateTime t) {
    return t.getHour() * 60 + t.getMinute();
  }

  private static int ceil(LocalDateTime t) {
    return floor(t) + (t.getSecond() > 0 || t.getNano() > 0 ? 1 : 0);
  }
}
