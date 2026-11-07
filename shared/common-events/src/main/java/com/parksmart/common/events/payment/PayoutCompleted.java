package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Host payout paid for the period. */
public record PayoutCompleted(
    UUID payoutId,
    UUID hostAccountId,
    BigDecimal amount,
    String currency,
    LocalDate periodStart,
    LocalDate periodEnd,
    Instant completedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return hostAccountId.toString();
  }
}
