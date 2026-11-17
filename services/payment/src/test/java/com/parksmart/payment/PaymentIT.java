package com.parksmart.payment;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.parksmart.payment.application.PaymentSaga;
import com.parksmart.payment.infrastructure.processor.FakeProcessor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Payment tests: a settable clock and helpers that play Booking's part of the saga. */
@Import(PaymentIT.Clocks.class)
abstract class PaymentIT extends IntegrationTest {

  static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    void set(Instant i) {
      now.set(i);
    }

    void advance(Duration d) {
      now.updateAndGet(i -> i.plus(d));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Clocks {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  @Autowired MutableClock clock;
  @Autowired PaymentSaga saga;
  @Autowired FakeProcessor processor;

  @BeforeEach
  void resetClock() {
    clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
  }

  /** A booking as Payment knows it. */
  record Deal(UUID bookingId, User driver, User host, BigDecimal amount) {}

  /** Plays BookingRequested: the intent exists, CREATED. */
  Deal requested(String amount) {
    Deal d = new Deal(UUID.randomUUID(), driver(), host(), new BigDecimal(amount));
    saga.bookingRequested(
        UUID.randomUUID(),
        d.bookingId(),
        d.driver().id(),
        d.host().id(),
        d.amount(),
        "INR",
        clock.instant().plus(Duration.ofMinutes(10)));
    return d;
  }

  JsonNode intent(Deal d) throws Exception {
    return body(
        get("/api/v1/payments/intents?bookingId=" + d.bookingId(), d.driver())
            .andExpect(status().isOk()));
  }

  ResultActions authorize(Deal d, String key, String token) throws Exception {
    String id = intent(d).get("id").asText();
    return mvc.perform(
        MockMvcRequestBuilders.post("/api/v1/payments/intents/" + id + "/authorize")
            .header("Authorization", "Bearer " + d.driver().token())
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("paymentMethodToken", token))));
  }

  /** Requested + authorized with fake_ok. */
  Deal authorized(String amount) throws Exception {
    Deal d = requested(amount);
    authorize(d, "auth-" + UUID.randomUUID(), "fake_ok").andExpect(status().isOk());
    return d;
  }

  /** Authorized + BookingConfirmed (capture). */
  Deal captured(String amount) throws Exception {
    Deal d = authorized(amount);
    confirm(d);
    return d;
  }

  void confirm(Deal d) {
    saga.bookingConfirmed(
        UUID.randomUUID(), d.bookingId(), d.driver().id(), d.host().id(), d.amount());
  }

  void cancel(Deal d, String refund) {
    saga.bookingCancelled(
        UUID.randomUUID(), d.bookingId(), d.driver().id(), d.host().id(), new BigDecimal(refund));
  }

  String statusOf(Deal d) {
    return jdbc.sql("SELECT status FROM payment_intents WHERE booking_id = :b")
        .param("b", d.bookingId())
        .query(String.class)
        .single();
  }

  /** Sum by account type and entry type for the booking. */
  BigDecimal ledger(Deal d, String accountType, String entryType) {
    return jdbc.sql(
            """
            SELECT COALESCE(sum(amount), 0) FROM ledger_entries
            WHERE booking_id = :b AND account_type = :a AND entry_type = :e
            """)
        .param("b", d.bookingId())
        .param("a", accountType)
        .param("e", entryType)
        .query(BigDecimal.class)
        .single();
  }

  long eventsFor(String type, Deal d) {
    return events(type, "bookingId", d.bookingId()).size();
  }
}
