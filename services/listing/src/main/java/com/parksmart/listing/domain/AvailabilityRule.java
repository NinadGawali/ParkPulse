package com.parksmart.listing.domain;

import java.time.DayOfWeek;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A weekly window in the listing's time zone: {@code [startMinute, endMinute)} minutes after local
 * midnight. {@code endMinute} 1440 means 24:00 (end of day).
 */
public record AvailabilityRule(DayOfWeek day, int startMinute, int endMinute) {

  private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):([0-5]\\d)|24:00");

  public AvailabilityRule {
    Objects.requireNonNull(day, "day");
    if (startMinute < 0 || endMinute > 1440 || startMinute >= endMinute) {
      throw new DomainException(
          "invalid-availability", "Each rule's endTime must be after startTime");
    }
  }

  /** From the API form: {@code HH:mm}, end may be {@code 24:00}. */
  public static AvailabilityRule parse(DayOfWeek day, String start, String end) {
    int startMinute = minutes(start);
    if (startMinute == 1440) {
      throw new DomainException("invalid-availability", "startTime cannot be 24:00");
    }
    return new AvailabilityRule(day, startMinute, minutes(end));
  }

  static int minutes(String time) {
    Matcher m = TIME.matcher(time == null ? "" : time);
    if (!m.matches()) {
      throw new DomainException("invalid-availability", "Times must be HH:mm (end may be 24:00)");
    }
    if (time.equals("24:00")) {
      return 1440;
    }
    return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
  }

  public String startTime() {
    return format(startMinute);
  }

  public String endTime() {
    return format(endMinute);
  }

  private static String format(int minute) {
    return "%02d:%02d".formatted(minute / 60, minute % 60);
  }
}
