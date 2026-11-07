package com.parksmart.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parksmart.payment.domain.Ledger.AccountType;
import com.parksmart.payment.domain.Ledger.EntryType;
import com.parksmart.payment.domain.Ledger.Posting;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerTest {

  static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  static PaymentIntent captured(String amount) {
    PaymentIntent i =
        PaymentIntent.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal(amount),
            "INR",
            NOW.plusSeconds(600),
            NOW);
    i.authorized("ref", NOW);
    i.captured(Ledger.commission(i.amount(), new BigDecimal("15.00")), NOW);
    return i;
  }

  static BigDecimal sum(List<Posting> postings, EntryType type) {
    return postings.stream()
        .filter(p -> p.type() == type)
        .map(Posting::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  static BigDecimal of(List<Posting> postings, AccountType account) {
    return postings.stream()
        .filter(p -> p.accountType() == account)
        .findFirst()
        .orElseThrow()
        .amount();
  }

  @Test
  void capture_splits_commission_and_balances() {
    PaymentIntent i = captured("150.00");
    assertThat(i.commissionAmount()).isEqualByComparingTo("22.50");
    List<Posting> p = Ledger.capture(i, i.commissionAmount());
    assertThat(sum(p, EntryType.DEBIT)).isEqualByComparingTo(sum(p, EntryType.CREDIT));
    assertThat(of(p, AccountType.HOST)).isEqualByComparingTo("127.50");
    assertThat(of(p, AccountType.PLATFORM)).isEqualByComparingTo("22.50");
    assertThat(Ledger.commission(new BigDecimal("33.33"), new BigDecimal("15")))
        .isEqualByComparingTo("5.00");
  }

  @Test
  void refunds_reverse_pro_rata_and_never_drift() {
    PaymentIntent i = captured("100.01"); // commission 15.00, host 85.01
    BigDecimal hostReversed = BigDecimal.ZERO;
    BigDecimal platformReversed = BigDecimal.ZERO;
    for (String part : new String[] {"33.33", "33.33", "33.35"}) {
      BigDecimal before = i.refundedAmount();
      BigDecimal applied = i.refunded(new BigDecimal(part), NOW);
      List<Posting> p = Ledger.refund(i, applied, before);
      assertThat(sum(p, EntryType.DEBIT)).isEqualByComparingTo(sum(p, EntryType.CREDIT));
      hostReversed = hostReversed.add(of(p, AccountType.HOST));
      platformReversed = platformReversed.add(of(p, AccountType.PLATFORM));
    }
    assertThat(i.status()).isEqualTo(PaymentIntent.Status.REFUNDED);
    // After a full refund in pieces, host and platform are back to exactly zero.
    assertThat(hostReversed).isEqualByComparingTo("85.01");
    assertThat(platformReversed).isEqualByComparingTo("15.00");
  }

  @Test
  void a_half_refund_halves_both_shares() {
    PaymentIntent i = captured("120.00"); // commission 18.00, host 102.00
    List<Posting> p = Ledger.refund(i, i.refunded(new BigDecimal("60.00"), NOW), BigDecimal.ZERO);
    assertThat(of(p, AccountType.HOST)).isEqualByComparingTo("51.00");
    assertThat(of(p, AccountType.PLATFORM)).isEqualByComparingTo("9.00");
    assertThat(of(p, AccountType.DRIVER)).isEqualByComparingTo("60.00");
    assertThat(i.status()).isEqualTo(PaymentIntent.Status.PARTIALLY_REFUNDED);
  }

  @Test
  void intent_state_rules() {
    PaymentIntent i =
        PaymentIntent.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal("50"),
            "INR",
            NOW.plusSeconds(600),
            NOW);
    i.checkAuthorizable(NOW.plusSeconds(599));
    assertThatThrownBy(() -> i.checkAuthorizable(NOW.plusSeconds(600)))
        .extracting("code")
        .isEqualTo("payment-window-closed");
    assertThatThrownBy(() -> i.refunded(BigDecimal.ONE, NOW))
        .extracting("code")
        .isEqualTo("illegal-payment-transition");
    i.voided(NOW);
    assertThatThrownBy(() -> i.checkAuthorizable(NOW))
        .extracting("code")
        .isEqualTo("payment-window-closed");
    PaymentIntent c = captured("10.00");
    assertThatThrownBy(() -> c.refunded(new BigDecimal("10.01"), NOW))
        .extracting("code")
        .isEqualTo("refund-exceeds-captured");
  }
}
