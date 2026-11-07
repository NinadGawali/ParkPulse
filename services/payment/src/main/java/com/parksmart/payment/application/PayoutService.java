package com.parksmart.payment.application;

import com.parksmart.common.events.payment.PayoutCompleted;
import com.parksmart.common.events.payment.PayoutFailed;
import com.parksmart.common.web.Cursor;
import com.parksmart.common.web.CursorPage;
import com.parksmart.payment.application.port.EventOutbox;
import com.parksmart.payment.application.port.LedgerRepository;
import com.parksmart.payment.application.port.LedgerRepository.Earnings;
import com.parksmart.payment.application.port.LedgerRepository.HostBalance;
import com.parksmart.payment.application.port.LedgerRepository.Payout;
import com.parksmart.payment.application.port.PaymentProcessor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Weekly host payouts: each host's unpaid ledger entries up to the period end are summed into one
 * payout. A failed payout releases its entries for the next run; a negative balance (refunds after
 * an earlier payout) is carried forward.
 */
@Service
public class PayoutService {

  private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

  private final LedgerRepository ledger;
  private final PaymentProcessor processor;
  private final EventOutbox outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public PayoutService(
      LedgerRepository ledger,
      PaymentProcessor processor,
      EventOutbox outbox,
      TransactionTemplate tx,
      Clock clock) {
    this.ledger = ledger;
    this.processor = processor;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Pays out everything earned up to and including {@code periodEnd} (UTC). Returns payouts made.
   */
  public int run(LocalDate periodEnd) {
    Instant cutoff = periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    int paid = 0;
    for (HostBalance balance : ledger.unpaidHostBalances(cutoff)) {
      try {
        Boolean ok = tx.execute(status -> payOne(balance, periodEnd, cutoff));
        if (Boolean.TRUE.equals(ok)) {
          paid++;
        }
      } catch (RuntimeException e) {
        log.warn("Payout for host {} failed; retried next run", balance.hostAccountId(), e);
      }
    }
    return paid;
  }

  private boolean payOne(HostBalance balance, LocalDate periodEnd, Instant cutoff) {
    Instant now = clock.instant();
    Payout payout =
        new Payout(
            UUID.randomUUID(),
            balance.hostAccountId(),
            balance.net(),
            "INR",
            balance.earliest(),
            periodEnd,
            "PENDING",
            null,
            now);
    ledger.insertPayout(payout);
    ledger.assignToPayout(balance.hostAccountId(), cutoff, payout.id());
    PaymentProcessor.Result result =
        processor.payout(payout.id(), payout.hostAccountId(), payout.amount(), payout.currency());
    if (result.ok()) {
      ledger.markPayout(payout.id(), "PAID", result.reference(), null, now);
      outbox.publish(
          new PayoutCompleted(
              payout.id(),
              payout.hostAccountId(),
              payout.amount(),
              payout.currency(),
              payout.periodStart(),
              payout.periodEnd(),
              now));
      return true;
    }
    ledger.releasePayout(payout.id());
    ledger.markPayout(payout.id(), "FAILED", null, result.failureCode(), now);
    outbox.publish(
        new PayoutFailed(
            payout.id(),
            payout.hostAccountId(),
            payout.amount(),
            payout.currency(),
            result.failureCode(),
            now));
    return false;
  }

  public CursorPage<Payout> payouts(UUID hostAccountId, String cursor, Integer limit) {
    Cursor after = Cursor.decode(cursor);
    int size = Cursor.limit(limit);
    List<Payout> rows =
        ledger.payouts(
            hostAccountId,
            after == null ? null : after.sortKey(),
            after == null ? null : after.id(),
            size + 1);
    if (rows.size() <= size) {
      return new CursorPage<>(rows, null);
    }
    List<Payout> items = rows.subList(0, size);
    Payout last = items.get(size - 1);
    return new CursorPage<>(items, new Cursor(last.createdAt(), last.id()).encode());
  }

  public Earnings earnings(UUID hostAccountId) {
    return ledger.earnings(hostAccountId);
  }
}
