package com.parksmart.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Double-entry postings for captures and refunds. Every posting set balances: total DEBIT = total
 * CREDIT.
 */
public final class Ledger {

  /** The platform's own ledger account. */
  public static final UUID PLATFORM_ACCOUNT = new UUID(0, 0);

  private Ledger() {}

  public enum AccountType {
    DRIVER,
    HOST,
    PLATFORM
  }

  public enum EntryType {
    DEBIT,
    CREDIT
  }

  public record Posting(
      AccountType accountType, UUID accountId, EntryType type, BigDecimal amount) {}

  /** Commission on a captured amount, HALF_UP to 2 decimals. */
  public static BigDecimal commission(BigDecimal amount, BigDecimal ratePercent) {
    return amount.multiply(ratePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
  }

  /** Capture: driver DEBIT total, host CREDIT total - commission, platform CREDIT commission. */
  public static List<Posting> capture(PaymentIntent intent, BigDecimal commission) {
    BigDecimal hostNet = intent.amount().subtract(commission);
    return List.of(
        new Posting(AccountType.DRIVER, intent.driverAccountId(), EntryType.DEBIT, intent.amount()),
        new Posting(AccountType.HOST, intent.hostAccountId(), EntryType.CREDIT, hostNet),
        new Posting(AccountType.PLATFORM, PLATFORM_ACCOUNT, EntryType.CREDIT, commission));
  }

  /**
   * Refund reversal, pro rata. The host's cumulative reversal is {@code hostNet x refundedTotal /
   * amount} (HALF_UP); this refund reverses the difference to what was already reversed, and the
   * platform takes the remainder, so the entries always balance and repeated partial refunds never
   * drift.
   *
   * @param refundedBefore total refunded before this refund
   */
  public static List<Posting> refund(
      PaymentIntent intent, BigDecimal refund, BigDecimal refundedBefore) {
    BigDecimal amount = intent.amount();
    BigDecimal hostNet = amount.subtract(intent.commissionAmount());
    BigDecimal hostBefore = share(hostNet, refundedBefore, amount);
    BigDecimal hostAfter = share(hostNet, refundedBefore.add(refund), amount);
    BigDecimal hostReversal = hostAfter.subtract(hostBefore);
    BigDecimal platformReversal = refund.subtract(hostReversal);
    return List.of(
        new Posting(AccountType.DRIVER, intent.driverAccountId(), EntryType.CREDIT, refund),
        new Posting(AccountType.HOST, intent.hostAccountId(), EntryType.DEBIT, hostReversal),
        new Posting(AccountType.PLATFORM, PLATFORM_ACCOUNT, EntryType.DEBIT, platformReversal));
  }

  private static BigDecimal share(BigDecimal part, BigDecimal numerator, BigDecimal denominator) {
    return part.multiply(numerator).divide(denominator, 2, RoundingMode.HALF_UP);
  }
}
