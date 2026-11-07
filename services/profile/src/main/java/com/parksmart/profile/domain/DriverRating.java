package com.parksmart.profile.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Driver rating cached from Review Service. {@code average} is null until the first review. */
public record DriverRating(BigDecimal average, int count, Instant asOf) {

  public static final DriverRating NONE = new DriverRating(null, 0, null);

  public DriverRating {
    if (count < 0) {
      throw new IllegalArgumentException("count must be >= 0");
    }
    if (count > 0) {
      Objects.requireNonNull(average, "average");
    }
  }
}
