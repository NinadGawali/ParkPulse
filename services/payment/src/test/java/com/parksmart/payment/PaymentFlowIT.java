package com.parksmart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class PaymentFlowIT extends PaymentIT {

  @Test
  void the_intent_appears_after_booking_requested_and_only_its_driver_sees_it() throws Exception {
    UUID unknown = UUID.randomUUID();
    get("/api/v1/payments/intents?bookingId=" + unknown, driver()).andExpect(status().isNotFound());
    Deal d = requested("150.00");
    saga.bookingRequested(
        UUID.randomUUID(),
        d.bookingId(),
        d.driver().id(),
        d.host().id(),
        d.amount(),
        "INR",
        clock.instant()); // a second request for the same booking is ignored
    get("/api/v1/payments/intents?bookingId=" + d.bookingId(), d.driver())
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.amount").value("150.00"))
        .andExpect(jsonPath("$.refundedAmount").value("0.00"));
    get("/api/v1/payments/intents?bookingId=" + d.bookingId(), d.host())
        .andExpect(status().isForbidden());
    get("/api/v1/payments/intents?bookingId=" + d.bookingId(), user("ADMIN"))
        .andExpect(status().isOk());
  }

  @Test
  void authorize_is_idempotent_and_never_charges_twice() throws Exception {
    Deal d = requested("60.00");
    String key = "auth-" + UUID.randomUUID();
    authorize(d, key, "fake_ok").andExpect(jsonPath("$.status").value("AUTHORIZED"));
    authorize(d, key, "fake_ok").andExpect(jsonPath("$.status").value("AUTHORIZED"));
    authorize(d, key, "fake_decline")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/idempotency-key-reused"));
    authorize(d, "auth-" + UUID.randomUUID(), "fake_decline")
        .andExpect(jsonPath("$.status").value("AUTHORIZED"));
    assertThat(eventsFor("PaymentAuthorized", d)).isEqualTo(1);
    var auth = events("PaymentAuthorized", "bookingId", d.bookingId()).get(0).get("payload");
    assertThat(auth.get("amount").asText()).isEqualTo("60.00");
    assertThat(auth.get("driverAccountId").asText()).isEqualTo(d.driver().id().toString());

    Deal other = requested("60.00");
    String otherIntent = intent(other).get("id").asText();
    mvc.perform(
            MockMvcRequestBuilders.post("/api/v1/payments/intents/" + otherIntent + "/authorize")
                .header("Authorization", "Bearer " + d.driver().token())
                .header("Idempotency-Key", "auth-" + UUID.randomUUID())
                .contentType("application/json")
                .content("{\"paymentMethodToken\":\"fake_ok\"}"))
        .andExpect(status().isForbidden());
    authorize(other, "short", "fake_ok").andExpect(status().isBadRequest());
  }

  @Test
  void a_declined_card_fails_and_the_window_closes_after_ten_minutes() throws Exception {
    Deal declined = requested("60.00");
    authorize(declined, "auth-" + UUID.randomUUID(), "fake_decline")
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureCode").value("card_declined"));
    assertThat(eventsFor("PaymentFailed", declined)).isEqualTo(1);

    Deal late = requested("60.00");
    clock.advance(Duration.ofMinutes(10));
    authorize(late, "auth-" + UUID.randomUUID(), "fake_ok")
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/payment-window-closed"));
    assertThat(statusOf(late)).isEqualTo("CREATED");
  }

  @Test
  void expiry_or_decline_voids_in_either_order() throws Exception {
    Deal beforePay = requested("60.00");
    saga.bookingEnded(
        UUID.randomUUID(),
        beforePay.bookingId(),
        beforePay.driver().id(),
        beforePay.host().id(),
        "expired");
    assertThat(statusOf(beforePay)).isEqualTo("VOIDED");
    authorize(beforePay, "auth-" + UUID.randomUUID(), "fake_ok").andExpect(status().isConflict());

    Deal afterPay = authorized("60.00");
    saga.bookingEnded(
        UUID.randomUUID(),
        afterPay.bookingId(),
        afterPay.driver().id(),
        afterPay.host().id(),
        "declined");
    assertThat(statusOf(afterPay)).isEqualTo("VOIDED");
    assertThat(eventsFor("PaymentVoided", afterPay)).isEqualTo(1);
    assertThat(ledger(afterPay, "DRIVER", "DEBIT")).isEqualByComparingTo("0");
  }

  @Test
  void confirmation_captures_with_commission_and_a_balanced_ledger() throws Exception {
    Deal d = captured("150.00");
    assertThat(statusOf(d)).isEqualTo("CAPTURED");
    assertThat(ledger(d, "DRIVER", "DEBIT")).isEqualByComparingTo("150.00");
    assertThat(ledger(d, "HOST", "CREDIT")).isEqualByComparingTo("127.50");
    assertThat(ledger(d, "PLATFORM", "CREDIT")).isEqualByComparingTo("22.50");
    var captured = events("PaymentCaptured", "bookingId", d.bookingId()).get(0).get("payload");
    assertThat(captured.get("commissionAmount").asText()).isEqualTo("22.50");
    assertThat(captured.get("hostNetAmount").asText()).isEqualTo("127.50");
    confirm(d); // duplicate signal: nothing more
    assertThat(eventsFor("PaymentCaptured", d)).isEqualTo(1);
  }

  @Test
  void a_rejected_capture_emits_payment_capture_failed() throws Exception {
    Deal d = requested("60.00");
    authorize(d, "auth-" + UUID.randomUUID(), "fake_capture_fail").andExpect(status().isOk());
    confirm(d);
    assertThat(statusOf(d)).isEqualTo("FAILED");
    assertThat(eventsFor("PaymentCaptureFailed", d)).isEqualTo(1);
    assertThat(ledger(d, "DRIVER", "DEBIT")).isEqualByComparingTo("0");
  }

  @Test
  void cancellation_voids_full_refunds_and_captures_then_refunds_partials() throws Exception {
    Deal full = authorized("120.00");
    cancel(full, "120.00");
    assertThat(statusOf(full)).isEqualTo("VOIDED");

    Deal partialAuthorized = authorized("120.00");
    cancel(partialAuthorized, "60.00");
    assertThat(statusOf(partialAuthorized)).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(ledger(partialAuthorized, "DRIVER", "CREDIT")).isEqualByComparingTo("60.00");
    assertThat(ledger(partialAuthorized, "HOST", "DEBIT")).isEqualByComparingTo("51.00");
    assertThat(ledger(partialAuthorized, "PLATFORM", "DEBIT")).isEqualByComparingTo("9.00");

    Deal capturedFull = captured("120.00");
    cancel(capturedFull, "120.00");
    assertThat(statusOf(capturedFull)).isEqualTo("REFUNDED");
    assertThat(
            ledger(capturedFull, "HOST", "CREDIT").subtract(ledger(capturedFull, "HOST", "DEBIT")))
        .isEqualByComparingTo("0");

    Deal kept = captured("120.00");
    cancel(kept, "0.00");
    assertThat(statusOf(kept)).isEqualTo("CAPTURED");
    assertThat(eventsFor("PaymentRefunded", kept)).isZero();
  }

  @Test
  void events_that_do_not_match_the_intent_are_ignored() throws Exception {
    Deal d = authorized("60.00");
    saga.bookingConfirmed(
        UUID.randomUUID(), d.bookingId(), d.driver().id(), d.host().id(), new BigDecimal("1.00"));
    saga.bookingCancelled(
        UUID.randomUUID(),
        d.bookingId(),
        UUID.randomUUID(),
        d.host().id(),
        new BigDecimal("60.00"));
    assertThat(statusOf(d)).isEqualTo("AUTHORIZED");
  }

  @Test
  void admin_refunds_are_admin_only_idempotent_and_bounded() throws Exception {
    Deal d = captured("100.00");
    String id = intent(d).get("id").asText();
    String path = "/admin/v1/payments/intents/" + id + "/refunds";
    String key = "refund-" + UUID.randomUUID();
    var body = Map.of("amount", "30.00", "reason", "dispute");
    mvc.perform(post(path, key, body, d.driver())).andExpect(status().isForbidden());
    User admin = user("ADMIN");
    mvc.perform(post(path, key, body, admin))
        .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
        .andExpect(jsonPath("$.refundedAmount").value("30.00"));
    mvc.perform(post(path, key, body, admin))
        .andExpect(jsonPath("$.refundedAmount").value("30.00"));
    mvc.perform(
            post(
                path,
                "refund-" + UUID.randomUUID(),
                Map.of("amount", "70.01", "reason", "x"),
                admin))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/refund-exceeds-captured"));
    assertThat(eventsFor("PaymentRefunded", d)).isEqualTo(1);
  }

  private org.springframework.test.web.servlet.RequestBuilder post(
      String path, String key, Object body, User user) throws Exception {
    return MockMvcRequestBuilders.post(path)
        .header("Authorization", "Bearer " + user.token())
        .header("Idempotency-Key", key)
        .contentType("application/json")
        .content(json.writeValueAsString(body));
  }
}
