package com.parksmart.booking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Driver-cancellation refund rules (common.yaml#CancellationPolicy). Host cancellations and
 * declines always refund 100%.
 */
public enum CancellationPolicy {
  /** 100% if cancelled at least 1h before start, else 0%. */
  FLEXIBLE,
  /** 100% at least 24h before start, 50% at least 1h before, else 0%. */
  MODERATE,
  /** 50% at least 24h before start, else 0%. */
  STRICT;

  private static final BigDecimal HALF = new BigDecimal("0.5");

  /** Refund for a driver cancelling {@code untilStart} before the start (negative = started). */
  public BigDecimal driverRefund(BigDecimal total, Duration untilStart) {
    boolean dayAhead = untilStart.compareTo(Duration.ofHours(24)) >= 0;
    boolean hourAhead = untilStart.compareTo(Duration.ofHours(1)) >= 0;
    BigDecimal fraction =
        switch (this) {
          case FLEXIBLE -> hourAhead ? BigDecimal.ONE : BigDecimal.ZERO;
          case MODERATE -> dayAhead ? BigDecimal.ONE : hourAhead ? HALF : BigDecimal.ZERO;
          case STRICT -> dayAhead ? HALF : BigDecimal.ZERO;
        };
    return total.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
  }
}
