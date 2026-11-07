package com.parksmart.payment.application.port;

import com.parksmart.payment.domain.Ledger.Posting;
import com.parksmart.payment.domain.PaymentIntent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Ledger entries, commission rules and payouts. */
public interface LedgerRepository {

  /** Records a balanced set of postings for a capture or refund. */
  void post(
      PaymentIntent intent,
      String referenceType,
      UUID referenceId,
      List<Posting> postings,
      Instant at);

  /** Audit row for a refund (its ID is also the processor idempotency key). */
  void recordRefund(
      UUID refundId,
      UUID intentId,
      BigDecimal amount,
      String source,
      UUID actor,
      String reason,
      Instant at);

  /** The commission rate (percent) effective at {@code at}. */
  BigDecimal commissionRateAt(Instant at);

  record HostBalance(UUID hostAccountId, BigDecimal net, LocalDate earliest) {}

  /** Hosts whose not-yet-paid entries (created before {@code cutoff}) net to a positive amount. */
  List<HostBalance> unpaidHostBalances(Instant cutoff);

  /** Serializes payout work for one host (transaction-scoped; across instances). */
  void lockHost(UUID hostAccountId);

  /**
   * Attaches a host's unpaid entries created before {@code cutoff} to the payout.
   *
   * @return the net (credits - debits) of exactly the entries attached
   */
  BigDecimal assignToPayout(UUID hostAccountId, Instant cutoff, UUID payoutId);

  void setPayoutAmount(UUID payoutId, BigDecimal amount);

  /** PENDING payouts created before {@code olderThan} (a run that stopped before finishing). */
  List<Payout> stalePending(Instant olderThan);

  /** A failed payout's entries become unpaid again (picked up by the next run). */
  void releasePayout(UUID payoutId);

  record Payout(
      UUID id,
      UUID hostAccountId,
      BigDecimal amount,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      String status,
      String failureReason,
      Instant createdAt) {}

  void insertPayout(Payout payout);

  /** Moves a PENDING payout to its final state; false if it was already finalized. */
  boolean finishPayout(
      UUID payoutId, String status, String processorRef, String failureReason, Instant at);

  /** Newest first, keyset after {@code (afterCreated, afterId)}. */
  List<Payout> payouts(UUID hostAccountId, Instant afterCreated, UUID afterId, int limit);

  record Earnings(BigDecimal totalEarned, BigDecimal totalPaidOut, BigDecimal pendingPayout) {}

  Earnings earnings(UUID hostAccountId);
}
