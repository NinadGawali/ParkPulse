package com.parksmart.common.events.payment;

import com.parksmart.common.events.EventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Host payout failed; entries stay unpaid for the next run. */
public record PayoutFailed(
    UUID payoutId,
    UUID hostAccountId,
    BigDecimal amount,
    String currency,
    String failureReason,
    Instant failedAt)
    implements EventPayload {

  @Override
  public String partitionKey() {
    return hostAccountId.toString();
  }
}
