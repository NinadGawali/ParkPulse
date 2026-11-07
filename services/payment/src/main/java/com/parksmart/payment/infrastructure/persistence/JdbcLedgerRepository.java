package com.parksmart.payment.infrastructure.persistence;

import static com.parksmart.payment.infrastructure.persistence.JdbcIntentRepository.instant;
import static com.parksmart.payment.infrastructure.persistence.JdbcIntentRepository.ts;

import com.parksmart.payment.application.port.LedgerRepository;
import com.parksmart.payment.domain.Ledger.EntryType;
import com.parksmart.payment.domain.Ledger.Posting;
import com.parksmart.payment.domain.PaymentIntent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLedgerRepository implements LedgerRepository {

  /** Host balance = CREDITs - DEBITs. */
  private static final String SIGNED =
      "CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END";

  private static final String SIGNED_E =
      "CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE -e.amount END";

  private final JdbcClient jdbc;

  JdbcLedgerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void post(
      PaymentIntent intent,
      String referenceType,
      UUID referenceId,
      List<Posting> postings,
      Instant at) {
    BigDecimal debits = BigDecimal.ZERO;
    BigDecimal credits = BigDecimal.ZERO;
    for (Posting p : postings) {
      if (p.type() == EntryType.DEBIT) {
        debits = debits.add(p.amount());
      } else {
        credits = credits.add(p.amount());
      }
    }
    if (debits.compareTo(credits) != 0) {
      throw new IllegalStateException("Unbalanced postings for " + referenceId);
    }
    for (Posting p : postings) {
      jdbc.sql(
              """
              INSERT INTO ledger_entries (id, account_id, account_type, entry_type, amount, currency,
                reference_type, reference_id, intent_id, booking_id, created_at)
              VALUES (:id, :account, :accountType, :entryType, :amount, :currency, :refType, :refId,
                :intent, :booking, :at)
              """)
          .param("id", UUID.randomUUID())
          .param("account", p.accountId())
          .param("accountType", p.accountType().name())
          .param("entryType", p.type().name())
          .param("amount", p.amount())
          .param("currency", intent.currency())
          .param("refType", referenceType)
          .param("refId", referenceId)
          .param("intent", intent.id())
          .param("booking", intent.bookingId())
          .param("at", ts(at))
          .update();
    }
  }

  @Override
  public BigDecimal commissionRateAt(Instant at) {
    return jdbc.sql(
            """
            SELECT rate_percent FROM commission_rules
            WHERE effective_from <= :at AND (effective_to IS NULL OR effective_to > :at)
            ORDER BY effective_from DESC LIMIT 1
            """)
        .param("at", ts(at))
        .query(BigDecimal.class)
        .optional()
        .orElseThrow(() -> new IllegalStateException("No commission rule effective at " + at));
  }

  @Override
  public List<HostBalance> unpaidHostBalances(Instant cutoff) {
    return jdbc.sql(
            "SELECT account_id, sum("
                + SIGNED
                + ") AS net, min(created_at) AS earliest"
                + " FROM ledger_entries"
                + " WHERE account_type = 'HOST' AND payout_id IS NULL AND created_at < :cutoff"
                + " GROUP BY account_id HAVING sum("
                + SIGNED
                + ") > 0")
        .param("cutoff", ts(cutoff))
        .query((rs, n) ->
            new HostBalance(
                rs.getObject("account_id", UUID.class),
                rs.getBigDecimal("net"),
                instant(rs, "earliest").atOffset(java.time.ZoneOffset.UTC).toLocalDate()))
        .list();
  }

  @Override
  public void assignToPayout(UUID hostAccountId, Instant cutoff, UUID payoutId) {
    jdbc.sql(
            """
            UPDATE ledger_entries SET payout_id = :payout
            WHERE account_id = :host AND account_type = 'HOST' AND payout_id IS NULL
              AND created_at < :cutoff
            """)
        .param("payout", payoutId)
        .param("host", hostAccountId)
        .param("cutoff", ts(cutoff))
        .update();
  }

  @Override
  public void releasePayout(UUID payoutId) {
    jdbc.sql("UPDATE ledger_entries SET payout_id = NULL WHERE payout_id = :p")
        .param("p", payoutId)
        .update();
  }

  @Override
  public void insertPayout(Payout p) {
    jdbc.sql(
            """
            INSERT INTO payouts (id, host_account_id, amount, currency, period_start, period_end,
              status, created_at, updated_at)
            VALUES (:id, :host, :amount, :currency, :start, :end, :status, :at, :at)
            """)
        .param("id", p.id())
        .param("host", p.hostAccountId())
        .param("amount", p.amount())
        .param("currency", p.currency())
        .param("start", p.periodStart())
        .param("end", p.periodEnd())
        .param("status", p.status())
        .param("at", ts(p.createdAt()))
        .update();
  }

  @Override
  public void markPayout(
      UUID payoutId, String status, String processorRef, String failureReason, Instant at) {
    jdbc.sql(
            """
            UPDATE payouts SET status = :status, processor_payout_ref = :ref,
              failure_reason = :reason, updated_at = :at
            WHERE id = :id
            """)
        .param("status", status)
        .param("ref", processorRef)
        .param("reason", failureReason)
        .param("at", ts(at))
        .param("id", payoutId)
        .update();
  }

  @Override
  public List<Payout> payouts(UUID hostAccountId, Instant afterCreated, UUID afterId, int limit) {
    String keyset =
        afterCreated == null
            ? ""
            : " AND (created_at < :after OR (created_at = :after AND id < :afterId))";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT id, host_account_id, amount, currency, period_start, period_end, status,"
                    + " failure_reason, created_at FROM payouts WHERE host_account_id = :host"
                    + keyset
                    + " ORDER BY created_at DESC, id DESC LIMIT :limit")
            .param("host", hostAccountId)
            .param("limit", limit);
    if (afterCreated != null) {
      spec = spec.param("after", ts(afterCreated)).param("afterId", afterId);
    }
    return spec.query((rs, n) ->
            new Payout(
                rs.getObject("id", UUID.class),
                rs.getObject("host_account_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getString("status"),
                rs.getString("failure_reason"),
                instant(rs, "created_at")))
        .list();
  }

  @Override
  public Earnings earnings(UUID hostAccountId) {
    return jdbc.sql(
            "SELECT COALESCE(sum("
                + SIGNED_E
                + "), 0) AS earned,"
                + " COALESCE(sum("
                + SIGNED_E
                + ") FILTER (WHERE p.status = 'PAID'), 0) AS paid,"
                + " COALESCE(sum("
                + SIGNED_E
                + ") FILTER (WHERE p.status IS DISTINCT FROM 'PAID'), 0)"
                + " AS pending"
                + " FROM ledger_entries e LEFT JOIN payouts p ON p.id = e.payout_id"
                + " WHERE e.account_id = :host AND e.account_type = 'HOST'")
        .param("host", hostAccountId)
        .query((rs, n) ->
            new Earnings(
                rs.getBigDecimal("earned").setScale(2),
                rs.getBigDecimal("paid").setScale(2),
                rs.getBigDecimal("pending").setScale(2)))
        .single();
  }
}
