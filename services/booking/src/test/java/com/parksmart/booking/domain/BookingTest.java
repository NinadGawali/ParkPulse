package com.parksmart.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingTest {

  static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  static final Instant START = Instant.parse("2026-11-09T10:00:00Z"); // 48h ahead

  static Booking booking(
      boolean instant, CancellationPolicy policy, Instant start, Duration length) {
    return Booking.request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        start,
        start.plus(length),
        new Booking.Terms(UUID.randomUUID(), new BigDecimal("45.00"), "INR", instant, policy, 15),
        "key-12345678",
        "hash",
        NOW);
  }

  static Booking confirmed(CancellationPolicy policy, Instant start) {
    Booking b = booking(true, policy, start, Duration.ofHours(2));
    b.paymentAuthorized(NOW);
    return b;
  }

  @Test
  void total_is_price_times_hours_rounded_half_up() {
    assertThat(Booking.total(new BigDecimal("45.00"), START, START.plus(Duration.ofMinutes(45))))
        .isEqualByComparingTo("33.75");
    assertThat(Booking.total(new BigDecimal("10.01"), START, START.plus(Duration.ofMinutes(45))))
        .isEqualByComparingTo("7.51"); // 7.5075
    Booking b = booking(true, CancellationPolicy.FLEXIBLE, START, Duration.ofMinutes(90));
    assertThat(b.totalAmount()).isEqualByComparingTo("67.50");
    assertThat(b.blockedUntil()).isEqualTo(START.plus(Duration.ofMinutes(105)));
    assertThat(b.paymentDueBy()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
  }

  @Test
  void window_rules() {
    assertThatThrownBy(() -> Booking.validateWindow(START, START.plusSeconds(60 * 20), NOW))
        .extracting("code")
        .isEqualTo("invalid-booking-window");
    assertThatThrownBy(
            () -> Booking.validateWindow(START.plusSeconds(60), START.plusSeconds(3660), NOW))
        .hasMessageContaining("15-minute");
    assertThatThrownBy(() -> Booking.validateWindow(NOW, NOW.plusSeconds(3600), NOW))
        .hasMessageContaining("15 minutes from now");
    assertThatThrownBy(() -> Booking.validateWindow(START, START.plus(Duration.ofDays(31)), NOW))
        .hasMessageContaining("30 days");
    Booking.validateWindow(START, START.plus(Duration.ofDays(30)), NOW);
    Booking.validateWindow(NOW.plus(Duration.ofMinutes(15)), NOW.plus(Duration.ofMinutes(45)), NOW);
  }

  @Test
  void request_to_book_waits_for_the_host_until_24h_or_the_start() {
    Booking far = booking(false, CancellationPolicy.FLEXIBLE, START, Duration.ofHours(1));
    assertThat(far.paymentAuthorized(NOW)).isEqualTo(BookingStatus.PENDING_PAYMENT);
    assertThat(far.status()).isEqualTo(BookingStatus.AWAITING_HOST_APPROVAL);
    assertThat(far.hostDecisionDueBy()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    Instant soon = NOW.plus(Duration.ofHours(3));
    Booking near = booking(false, CancellationPolicy.FLEXIBLE, soon, Duration.ofHours(1));
    near.paymentAuthorized(NOW);
    assertThat(near.hostDecisionDueBy()).isEqualTo(soon);
  }

  @Test
  void refund_policies() {
    BigDecimal total = new BigDecimal("90.00");
    assertThat(CancellationPolicy.FLEXIBLE.driverRefund(total, Duration.ofHours(1)))
        .isEqualByComparingTo("90.00");
    assertThat(CancellationPolicy.FLEXIBLE.driverRefund(total, Duration.ofMinutes(59)))
        .isEqualByComparingTo("0.00");
    assertThat(CancellationPolicy.MODERATE.driverRefund(total, Duration.ofHours(24)))
        .isEqualByComparingTo("90.00");
    assertThat(CancellationPolicy.MODERATE.driverRefund(total, Duration.ofHours(23)))
        .isEqualByComparingTo("45.00");
    assertThat(CancellationPolicy.MODERATE.driverRefund(total, Duration.ofMinutes(30)))
        .isEqualByComparingTo("0.00");
    assertThat(CancellationPolicy.STRICT.driverRefund(total, Duration.ofDays(3)))
        .isEqualByComparingTo("45.00");
    assertThat(CancellationPolicy.STRICT.driverRefund(total, Duration.ofHours(2)))
        .isEqualByComparingTo("0.00");
    assertThat(CancellationPolicy.FLEXIBLE.driverRefund(total, Duration.ofHours(-1)))
        .isEqualByComparingTo("0.00");
  }

  @Test
  void cancellations() {
    Booking awaiting = booking(false, CancellationPolicy.STRICT, START, Duration.ofHours(2));
    awaiting.paymentAuthorized(NOW);
    awaiting.cancelByDriver(NOW);
    assertThat(awaiting.refundAmount()).isEqualByComparingTo(awaiting.totalAmount());

    Booking strict = confirmed(CancellationPolicy.STRICT, START);
    strict.cancelByDriver(NOW.plus(Duration.ofDays(1)).plusSeconds(1));
    assertThat(strict.refundAmount()).isEqualByComparingTo("0.00");

    Booking host = confirmed(CancellationPolicy.STRICT, START);
    host.cancelByHost(NOW);
    assertThat(host.status()).isEqualTo(BookingStatus.CANCELLED_BY_HOST);
    assertThat(host.refundAmount()).isEqualByComparingTo(host.totalAmount());

    Booking pending = booking(true, CancellationPolicy.FLEXIBLE, START, Duration.ofHours(1));
    assertThatThrownBy(() -> pending.cancelByDriver(NOW))
        .extracting("code")
        .isEqualTo("illegal-booking-transition");
  }

  @Test
  void timers_respect_their_deadlines() {
    Booking pending = booking(true, CancellationPolicy.FLEXIBLE, START, Duration.ofHours(1));
    assertThatThrownBy(() -> pending.expirePayment(NOW.plusSeconds(599)))
        .extracting("code")
        .isEqualTo("illegal-booking-transition");
    pending.expirePayment(NOW.plusSeconds(600));
    assertThat(pending.status()).isEqualTo(BookingStatus.EXPIRED);

    Booking c = confirmed(CancellationPolicy.FLEXIBLE, START);
    assertThatThrownBy(() -> c.markNoShow(START.plus(Duration.ofMinutes(29))))
        .isInstanceOf(DomainException.class);
    c.markNoShow(START.plus(Duration.ofMinutes(30)));
    assertThat(c.status()).isEqualTo(BookingStatus.NO_SHOW);
  }

  @Test
  void check_in_window_and_completion() {
    Booking c = confirmed(CancellationPolicy.FLEXIBLE, START);
    assertThatThrownBy(() -> c.checkIn(START.minus(Duration.ofMinutes(16))))
        .extracting("code")
        .isEqualTo("check-in-window");
    assertThatThrownBy(() -> c.checkIn(START.plus(Duration.ofMinutes(30))))
        .extracting("code")
        .isEqualTo("check-in-window");
    c.checkIn(START.minus(Duration.ofMinutes(15)));
    assertThatThrownBy(() -> c.completeAtEnd(START.plus(Duration.ofMinutes(119))))
        .isInstanceOf(DomainException.class);
    c.completeAtEnd(START.plus(Duration.ofHours(2)));
    assertThat(c.status()).isEqualTo(BookingStatus.COMPLETED);
  }

  @Test
  void withdrawn_listing_ends_unstarted_bookings_only() {
    Booking c = confirmed(CancellationPolicy.STRICT, START);
    c.listingWithdrawn(NOW);
    assertThat(c.status()).isEqualTo(BookingStatus.CANCELLED_BY_HOST);
    Booking started = confirmed(CancellationPolicy.STRICT, START);
    assertThatThrownBy(() -> started.listingWithdrawn(START.plusSeconds(1)))
        .isInstanceOf(DomainException.class);
    Booking pending = booking(true, CancellationPolicy.STRICT, START, Duration.ofHours(1));
    pending.listingWithdrawn(NOW);
    assertThat(pending.status()).isEqualTo(BookingStatus.EXPIRED);
  }

  @Test
  void own_listing_cannot_be_booked() {
    UUID me = UUID.randomUUID();
    assertThatThrownBy(() ->
            Booking.request(
                UUID.randomUUID(),
                me,
                UUID.randomUUID(),
                null,
                START,
                START.plusSeconds(3600),
                new Booking.Terms(me, BigDecimal.TEN, "INR", true, CancellationPolicy.FLEXIBLE, 0),
                "key-12345678",
                "h",
                NOW))
        .extracting("code")
        .isEqualTo("cannot-book-own-listing");
  }
}
