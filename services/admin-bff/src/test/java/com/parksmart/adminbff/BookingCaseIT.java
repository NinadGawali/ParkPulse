package com.parksmart.adminbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class BookingCaseIT extends IntegrationTest {

  final UUID bookingId = UUID.randomUUID();
  final UUID listing = UUID.randomUUID();
  final UUID driver = UUID.randomUUID();
  final UUID host = UUID.randomUUID();
  final UUID intent = UUID.randomUUID();

  void bookingExists() {
    STUB.on(
        "GET",
        "/api/v1/bookings/" + bookingId,
        200,
        Fixtures.booking(bookingId, listing, driver, host));
  }

  void intentExists(String status) {
    STUB.on(
        "GET",
        "/api/v1/payments/intents?bookingId=" + bookingId,
        200,
        Fixtures.intent(intent, bookingId, status, "0.00"));
  }

  @Test
  void case_view_composes_booking_payment_listing_and_both_profiles() throws Exception {
    bookingExists();
    intentExists("CAPTURED");
    STUB.on(
        "GET", "/api/v1/listings/" + listing, 200, Fixtures.listing(listing, host, "PUBLISHED"));
    STUB.on("GET", "/api/v1/profiles/" + driver, 200, Fixtures.profile(driver, "Driver D"));
    STUB.on("GET", "/api/v1/profiles/" + host, 200, Fixtures.profile(host, "Host H"));

    var result =
        get("/admin/v1/bookings/" + bookingId, admin())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.partial").value(false))
            .andExpect(jsonPath("$.booking.bookingId").value(bookingId.toString()))
            .andExpect(jsonPath("$.booking.totalAmount").value("120.00"))
            .andExpect(jsonPath("$.booking.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.booking.refundAmount").isEmpty())
            .andExpect(jsonPath("$.booking.vehicleId").doesNotExist())
            .andExpect(jsonPath("$.payment.intentId").value(intent.toString()))
            .andExpect(jsonPath("$.payment.status").value("CAPTURED"))
            .andExpect(jsonPath("$.listing.title").value("Covered spot"))
            .andExpect(jsonPath("$.driver.displayName").value("Driver D"))
            .andExpect(jsonPath("$.host.displayName").value("Host H"));
    assertThat(result.andReturn().getResponse().getContentAsString())
        .doesNotContain("leak@example.com")
        .doesNotContain("+919999999999");
  }

  @Test
  void missing_intent_is_null_and_failing_parts_make_the_view_partial() throws Exception {
    bookingExists();
    // payment: 404 (not created yet); listing: 500; profiles: one missing, one slow.
    STUB.on("GET", "/api/v1/listings/" + listing, 500, "");
    STUB.slow("GET", "/api/v1/profiles/" + host, 2_000, Fixtures.profile(host, "Host H"));

    get("/admin/v1/bookings/" + bookingId, admin())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.partial").value(true))
        .andExpect(jsonPath("$.payment").isEmpty())
        .andExpect(jsonPath("$.listing").isEmpty())
        .andExpect(jsonPath("$.driver").isEmpty())
        .andExpect(jsonPath("$.host").isEmpty())
        .andExpect(jsonPath("$.booking.bookingId").value(bookingId.toString()));
    // Idempotent GETs are retried once after a timeout.
    assertThat(STUB.calls("GET", "/api/v1/profiles/" + host)).hasSize(2);
  }

  @Test
  void unknown_booking_is_404_and_booking_down_is_503() throws Exception {
    get("/admin/v1/bookings/" + bookingId, admin())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/not-found"));
    assertThat(STUB.calls("GET", "/api/v1/payments/")).isEmpty();

    STUB.on("GET", "/api/v1/bookings/" + bookingId, 502, "");
    get("/admin/v1/bookings/" + bookingId, admin())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/booking-unavailable"));
  }

  @Test
  void a_rejected_token_downstream_is_401_not_partial() throws Exception {
    bookingExists();
    STUB.on("GET", "/api/v1/payments/intents?bookingId=" + bookingId, 401, "");
    get("/admin/v1/bookings/" + bookingId, admin()).andExpect(status().isUnauthorized());
  }

  @Test
  void refund_resolves_the_intent_and_forwards_key_and_body() throws Exception {
    intentExists("CAPTURED");
    STUB.on(
        "POST",
        "/admin/v1/payments/intents/" + intent + "/refunds",
        200,
        Fixtures.intent(intent, bookingId, "PARTIALLY_REFUNDED", "50.00"));
    User admin = admin();

    perform(
            MockMvcRequestBuilders.post("/admin/v1/bookings/" + bookingId + "/refunds")
                .header("Idempotency-Key", "dispute-42-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"50.00\",\"reason\":\"spot was blocked\"}"),
            admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intentId").value(intent.toString()))
        .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"))
        .andExpect(jsonPath("$.refundedAmount").value("50.00"));

    var forwarded = STUB.calls("POST", "/admin/v1/payments/intents/").get(0);
    assertThat(forwarded.header("Idempotency-Key")).isEqualTo("dispute-42-a");
    assertThat(forwarded.header("Authorization")).isEqualTo("Bearer " + admin.token());
    assertThat(forwarded.body())
        .isEqualTo("{\"amount\":\"50.00\",\"reason\":\"spot was blocked\"}");
  }

  @Test
  void refund_errors() throws Exception {
    // No intent for the booking.
    refund("refund-key-1", "{\"amount\":\"10.00\",\"reason\":\"x\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/payment-not-found"));

    // Payment's own rule passes through.
    intentExists("CAPTURED");
    STUB.on(
        "POST",
        "/admin/v1/payments/intents/" + intent + "/refunds",
        422,
        Fixtures.problem(422, "refund-exceeds-captured", "Only 120.00 can be refunded"));
    refund("refund-key-2", "{\"amount\":\"500.00\",\"reason\":\"x\"}")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/refund-exceeds-captured"));

    // Validated here before anything is forwarded.
    STUB.received.clear();
    refund("short", "{\"amount\":\"10.00\",\"reason\":\"x\"}").andExpect(status().isBadRequest());
    refund("refund-key-3", "{\"amount\":\"-1\",\"reason\":\"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("amount"));
    refund("refund-key-4", "{\"amount\":\"10.00\"}").andExpect(status().isBadRequest());
    perform(
            MockMvcRequestBuilders.post("/admin/v1/bookings/" + bookingId + "/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"10.00\",\"reason\":\"x\"}"),
            admin())
        .andExpect(status().isBadRequest());
    assertThat(STUB.received).isEmpty();
  }

  private org.springframework.test.web.servlet.ResultActions refund(String key, String body)
      throws Exception {
    return perform(
        MockMvcRequestBuilders.post("/admin/v1/bookings/" + bookingId + "/refunds")
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
        admin());
  }
}
