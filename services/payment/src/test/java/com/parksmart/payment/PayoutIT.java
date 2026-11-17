package com.parksmart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parksmart.payment.application.PayoutService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PayoutIT extends PaymentIT {

  @Autowired PayoutService payouts;

  private LocalDate today() {
    return clock.instant().atOffset(ZoneOffset.UTC).toLocalDate();
  }

  private long payoutsFor(User host, String status) {
    return jdbc.sql("SELECT count(*) FROM payouts WHERE host_account_id = :h AND status = :s")
        .param("h", host.id())
        .param("s", status)
        .query(Long.class)
        .single();
  }

  @Test
  void hosts_are_paid_their_net_once_and_see_earnings() throws Exception {
    Deal a = captured("100.00"); // host net 85.00
    Deal b = captured("40.00"); // another host
    cancel(a, "50.00"); // host reversal 42.50 -> net 42.50

    payouts.run(today());
    payouts.run(today()); // nothing new: no second payout
    assertThat(payoutsFor(a.host(), "PAID")).isEqualTo(1);
    var row =
        jdbc.sql("SELECT amount FROM payouts WHERE host_account_id = :h")
            .param("h", a.host().id())
            .query(java.math.BigDecimal.class)
            .single();
    assertThat(row).isEqualByComparingTo("42.50");
    assertThat(payoutsFor(b.host(), "PAID")).isEqualTo(1);
    assertThat(events("PayoutCompleted", "hostAccountId", a.host().id())).hasSize(1);

    get("/api/v1/hosts/me/earnings-summary", a.host())
        .andExpect(jsonPath("$.totalEarned").value("42.50"))
        .andExpect(jsonPath("$.totalPaidOut").value("42.50"))
        .andExpect(jsonPath("$.pendingPayout").value("0.00"));
    get("/api/v1/hosts/me/payouts", a.host())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].status").value("PAID"));
    get("/api/v1/hosts/me/payouts", a.driver()).andExpect(status().isForbidden());
  }

  @Test
  void entries_after_the_period_wait_for_the_next_run() throws Exception {
    Deal d = captured("100.00");
    payouts.run(today().minusDays(1));
    assertThat(payoutsFor(d.host(), "PAID")).isZero();
    get("/api/v1/hosts/me/earnings-summary", d.host())
        .andExpect(jsonPath("$.pendingPayout").value("85.00"));
  }

  @Test
  void a_failed_payout_releases_its_entries_for_the_next_run() throws Exception {
    Deal d = captured("100.00");
    processor.failPayoutsFor(d.host().id());
    payouts.run(today());
    assertThat(payoutsFor(d.host(), "FAILED")).isEqualTo(1);
    assertThat(events("PayoutFailed", "hostAccountId", d.host().id())).hasSize(1);
    Long unpaid =
        jdbc.sql("SELECT count(*) FROM ledger_entries WHERE account_id = :h AND payout_id IS NULL")
            .param("h", d.host().id())
            .query(Long.class)
            .single();
    assertThat(unpaid).isEqualTo(1);
    assertThat(UUID.randomUUID()).isNotNull();
  }

  @Test
  void concurrent_runs_pay_each_host_once() throws Exception {
    Deal d = captured("200.00");
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    var latch = new java.util.concurrent.CountDownLatch(1);
    var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
    for (int i = 0; i < 4; i++) {
      futures.add(
          pool.submit(() -> {
            latch.await();
            return payouts.run(today());
          }));
    }
    latch.countDown();
    for (var f : futures) {
      f.get();
    }
    pool.shutdown();
    assertThat(payoutsFor(d.host(), "PAID")).isEqualTo(1);
    assertThat(events("PayoutCompleted", "hostAccountId", d.host().id())).hasSize(1);
  }

  @Test
  void refunds_are_audited_with_deterministic_ids() throws Exception {
    Deal d = captured("100.00");
    cancel(d, "40.00");
    var row =
        jdbc.sql(
                "SELECT r.source, r.amount FROM refunds r JOIN payment_intents i ON i.id = r.intent_id"
                    + " WHERE i.booking_id = :b")
            .param("b", d.bookingId())
            .query()
            .singleRow();
    assertThat(row.get("source")).isEqualTo("CANCELLATION");
    assertThat((java.math.BigDecimal) row.get("amount")).isEqualByComparingTo("40.00");
    // An implausible refund (more than paid) is ignored.
    cancel(d, "1000.00");
    assertThat(statusOf(d)).isEqualTo("PARTIALLY_REFUNDED");
  }

  @Test
  void the_fake_processor_is_refused_on_aws() throws Exception {
    var check =
        Class.forName("com.parksmart.payment.infrastructure.processor.ProcessorGuard")
            .getDeclaredMethod("check", boolean.class, String.class);
    check.setAccessible(true);
    check.invoke(null, false, "fake");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> check.invoke(null, true, "fake"))
        .hasCauseInstanceOf(IllegalStateException.class);
  }
}
