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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Weekly host payouts, safe against crashes, retries and several instances:
 *
 * <ol>
 *   <li>Per host, under a host lock: insert a PENDING payout, attach the unpaid entries and take
 *       the amount from exactly those entries; commit.
 *   <li>Call the processor outside any transaction, with the payout ID as idempotency key.
 *   <li>Finalize PENDING -> PAID / FAILED (failed: entries released for the next run).
 * </ol>
 *
 * A run first resumes PENDING payouts left by a crash, with the same ID, so the processor can never
 * pay one twice. A unique index allows one live payout per host and period.
 */
@Service
public class PayoutService {

  private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
  private static final Duration STALE = Duration.ofMinutes(5);

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
    for (Payout stuck : ledger.stalePending(clock.instant().minus(STALE))) {
      pay(stuck);
    }
    Instant cutoff = periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    int paid = 0;
    for (HostBalance balance : ledger.unpaidHostBalances(cutoff)) {
      try {
        Payout pending = tx.execute(status -> reserve(balance, periodEnd, cutoff, status));
        if (pending != null && pay(pending)) {
          paid++;
        }
      } catch (DuplicateKeyException e) {
        log.info("Host {} already has a payout for {}", balance.hostAccountId(), periodEnd);
      } catch (RuntimeException e) {
        log.warn("Payout for host {} failed; retried next run", balance.hostAccountId(), e);
      }
    }
    return paid;
  }

  /** Step 1: PENDING payout with the amount of exactly the entries it took (null if nothing). */
  private Payout reserve(
      HostBalance balance,
      LocalDate periodEnd,
      Instant cutoff,
      org.springframework.transaction.TransactionStatus status) {
    ledger.lockHost(balance.hostAccountId());
    Payout draft =
        new Payout(
            UUID.randomUUID(),
            balance.hostAccountId(),
            new BigDecimal("0.01"), // replaced below by the assigned sum
            "INR",
            balance.earliest(),
            periodEnd,
            "PENDING",
            null,
            clock.instant());
    ledger.insertPayout(draft);
    BigDecimal amount = ledger.assignToPayout(balance.hostAccountId(), cutoff, draft.id());
    if (amount.signum() <= 0) {
      status.setRollbackOnly(); // another instance took them, or refunds cancelled them out
      return null;
    }
    ledger.setPayoutAmount(draft.id(), amount);
    return new Payout(
        draft.id(),
        draft.hostAccountId(),
        amount,
        draft.currency(),
        draft.periodStart(),
        periodEnd,
        "PENDING",
        null,
        draft.createdAt());
  }

  /** Steps 2 and 3. Returns true if the host was paid. */
  private boolean pay(Payout payout) {
    PaymentProcessor.Result result =
        processor.payout(payout.id(), payout.hostAccountId(), payout.amount(), payout.currency());
    Boolean ok =
        tx.execute(status -> {
          Instant now = clock.instant();
          if (result.ok()) {
            if (ledger.finishPayout(payout.id(), "PAID", result.reference(), null, now)) {
              outbox.publish(
                  new PayoutCompleted(
                      payout.id(),
                      payout.hostAccountId(),
                      payout.amount(),
                      payout.currency(),
                      payout.periodStart(),
                      payout.periodEnd(),
                      now));
            }
            return true;
          }
          if (ledger.finishPayout(payout.id(), "FAILED", null, result.failureCode(), now)) {
            ledger.releasePayout(payout.id());
            outbox.publish(
                new PayoutFailed(
                    payout.id(),
                    payout.hostAccountId(),
                    payout.amount(),
                    payout.currency(),
                    result.failureCode(),
                    now));
          }
          return false;
        });
    return Boolean.TRUE.equals(ok);
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
