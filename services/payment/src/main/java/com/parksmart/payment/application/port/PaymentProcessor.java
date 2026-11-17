package com.parksmart.payment.application.port;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The payment processor (FakeProcessor now; a Razorpay/Stripe adapter later). Card and bank data
 * never reach ParkSmart: the client sends a processor token. Every call passes ParkSmart's own ID
 * as the processor's idempotency key, so a retried call cannot charge twice.
 */
public interface PaymentProcessor {

  record Result(boolean ok, String reference, String failureCode) {
    public static Result ok(String reference) {
      return new Result(true, reference, null);
    }

    public static Result failed(String failureCode) {
      return new Result(false, null, failureCode);
    }
  }

  Result authorize(UUID intentId, String paymentMethodToken, BigDecimal amount, String currency);

  Result capture(UUID intentId, String authorizationRef, BigDecimal amount);

  void voidAuthorization(UUID intentId, String authorizationRef);

  Result refund(UUID refundId, String authorizationRef, BigDecimal amount);

  Result payout(UUID payoutId, UUID hostAccountId, BigDecimal amount, String currency);
}
