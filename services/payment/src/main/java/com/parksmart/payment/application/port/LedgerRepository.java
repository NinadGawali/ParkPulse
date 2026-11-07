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

  /** The commission rate (percent) effective at {@code at}. */
  BigDecimal commissionRateAt(Instant at);

  record HostBalance(UUID hostAccountId, BigDecimal net, LocalDate earliest) {}

  /** Hosts whose not-yet-paid entries (created before {@code cutoff}) net to a positive amount. */
  List<HostBalance> unpaidHostBalances(Instant cutoff);

  /** Attaches a host's unpaid entries created before {@code cutoff} to the payout. */
  void assignToPayout(UUID hostAccountId, Instant cutoff, UUID payoutId);

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

  void markPayout(
      UUID payoutId, String status, String processorRef, String failureReason, Instant at);

  /** Newest first, keyset after {@code (afterCreated, afterId)}. */
  List<Payout> payouts(UUID hostAccountId, Instant afterCreated, UUID afterId, int limit);

  record Earnings(BigDecimal totalEarned, BigDecimal totalPaidOut, BigDecimal pendingPayout) {}

  Earnings earnings(UUID hostAccountId);
}
