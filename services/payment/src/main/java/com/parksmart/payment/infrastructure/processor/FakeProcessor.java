package com.parksmart.payment.infrastructure.processor;

import com.parksmart.payment.application.port.PaymentProcessor;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/test stand-in for a real processor. Tokens: {@code fake_ok} authorizes, {@code
 * fake_decline} fails with {@code card_declined}, {@code fake_capture_fail} authorizes but the
 * capture is rejected. Only active with {@code parksmart.payment.processor=fake}; the aws profile
 * requires a real adapter, so it cannot start with this one.
 */
@Component
@ConditionalOnProperty(name = "parksmart.payment.processor", havingValue = "fake")
public class FakeProcessor implements PaymentProcessor {

  private static final String CAPTURE_FAILS = "capfail";

  private final Set<UUID> failingPayoutHosts = ConcurrentHashMap.newKeySet();

  /** Test hook: payouts to this host fail. */
  public void failPayoutsFor(UUID hostAccountId) {
    failingPayoutHosts.add(hostAccountId);
  }

  @Override
  public Result authorize(UUID intentId, String token, BigDecimal amount, String currency) {
    return switch (token) {
      case "fake_ok" -> Result.ok("fake_auth_" + intentId);
      case "fake_capture_fail" -> Result.ok("fake_auth_" + CAPTURE_FAILS + "_" + intentId);
      case "fake_decline" -> Result.failed("card_declined");
      default -> Result.failed("invalid_payment_method");
    };
  }

  @Override
  public Result capture(UUID intentId, String authorizationRef, BigDecimal amount) {
    return authorizationRef.contains(CAPTURE_FAILS)
        ? Result.failed("capture_rejected")
        : Result.ok("fake_capture_" + intentId);
  }

  @Override
  public void voidAuthorization(UUID intentId, String authorizationRef) {
    // Nothing is held for real.
  }

  @Override
  public Result refund(UUID refundId, String authorizationRef, BigDecimal amount) {
    return Result.ok("fake_refund_" + refundId);
  }

  @Override
  public Result payout(UUID payoutId, UUID hostAccountId, BigDecimal amount, String currency) {
    return failingPayoutHosts.contains(hostAccountId)
        ? Result.failed("payout_account_invalid")
        : Result.ok("fake_payout_" + payoutId);
  }
}
