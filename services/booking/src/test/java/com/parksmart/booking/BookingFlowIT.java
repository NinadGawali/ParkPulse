package com.parksmart.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.parksmart.booking.application.port.BookingRepository.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingFlowIT extends BookingIT {

  @Test
  void create_holds_the_slot_and_snapshots_the_listing_terms() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), true, "MODERATE", 15);
    Instant start = slot(48);
    User driver = driver();
    book(
            driver,
            "key-create-1-" + UUID.randomUUID(),
            body(listing, start, start.plus(Duration.ofMinutes(150))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
        .andExpect(jsonPath("$.hostAccountId").value(host.id().toString()))
        .andExpect(jsonPath("$.pricePerHour").value("60.00"))
        .andExpect(jsonPath("$.totalAmount").value("150.00"))
        .andExpect(jsonPath("$.cancellationPolicy").value("MODERATE"))
        .andExpect(jsonPath("$.bufferMinutes").value(15))
        .andExpect(
            jsonPath("$.paymentDueBy")
                .value(clock.instant().plus(Duration.ofMinutes(10)).toString()));
    JsonNode requested = events("BookingRequested", "listingId", listing).get(0).get("payload");
    assertThat(requested.get("totalAmount").asText()).isEqualTo("150.00");
    assertThat(requested.get("instantBook").asBoolean()).isTrue();
  }

  @Test
  void idempotency_key_replays_and_detects_reuse() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    User driver = driver();
    Instant start = slot(24);
    Map<String, Object> body = body(listing, start, start.plus(Duration.ofHours(1)));
    String key = "replay-" + UUID.randomUUID();
    String first =
        body(book(driver, key, body).andExpect(status().isAccepted())).get("id").asText();
    book(driver, key, body).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
    body.put("endTime", start.plus(Duration.ofHours(2)).toString());
    book(driver, key, body)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/idempotency-key-reused"));
    // The same key belongs to each driver separately.
    book(driver(), key, body(listing, slot(72), slot(72).plus(Duration.ofHours(1))))
        .andExpect(status().isAccepted());
    assertThat(events("BookingRequested", "listingId", listing)).hasSize(2);
  }

  @Test
  void request_validation() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), true, "FLEXIBLE", 0);
    Instant start = slot(24);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/bookings")
                .header("Authorization", "Bearer " + driver().token())
                .contentType("application/json")
                .content(json.writeValueAsString(body(listing, start, start.plusSeconds(3600)))))
        .andExpect(status().isBadRequest());
    book(driver(), "short", body(listing, start, start.plusSeconds(3600)))
        .andExpect(status().isBadRequest());
    book(
            driver(),
            "k-" + UUID.randomUUID(),
            body(listing, start.plusSeconds(60), start.plusSeconds(3600)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/invalid-booking-window"));
    book(
            driver(),
            "k-" + UUID.randomUUID(),
            body(listing, start, start.plus(Duration.ofMinutes(15))))
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/invalid-booking-window"));
    Instant soon = slot(0).minus(Duration.ofMinutes(15));
    book(driver(), "k-" + UUID.randomUUID(), body(listing, soon, soon.plusSeconds(3600)))
        .andExpect(status().isUnprocessableEntity());
    // The host cannot book their own listing (a host account also has DRIVER).
    book(host, "k-" + UUID.randomUUID(), body(listing, start, start.plusSeconds(3600)))
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/cannot-book-own-listing"));
    book(
            driver(),
            "k-" + UUID.randomUUID(),
            body(UUID.randomUUID(), start, start.plusSeconds(3600)))
        .andExpect(status().isNotFound());
    book(user("HOST"), "k-" + UUID.randomUUID(), body(listing, start, start.plusSeconds(3600)))
        .andExpect(status().isForbidden());
  }

  @Test
  void unbookable_listings_are_422_with_the_reason() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    var q = listings.quotes.get(listing);
    listings.quotes.put(
        listing,
        new com.parksmart.booking.application.port.ListingQuotes.Quote(
            q.listingId(),
            q.hostAccountId(),
            "PUBLISHED",
            false,
            "BLACKED_OUT",
            q.pricePerHour(),
            q.currency(),
            q.instantBook(),
            q.cancellationPolicy(),
            q.bufferMinutes()));
    Instant start = slot(24);
    book(driver(), "k-" + UUID.randomUUID(), body(listing, start, start.plusSeconds(3600)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/listing-not-bookable"))
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("BLACKED_OUT")));
  }

  @Test
  void overlaps_and_the_buffer_are_rejected_by_the_database() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 30);
    Instant start = slot(24);
    booked(driver(), listing, start, start.plus(Duration.ofHours(2)));
    // Starts inside the 30-minute buffer after the first booking ends.
    Instant inBuffer = start.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(15));
    book(driver(), "k-" + UUID.randomUUID(), body(listing, inBuffer, inBuffer.plusSeconds(3600)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/booking-overlap"));
    Instant afterBuffer = start.plus(Duration.ofMinutes(150));
    booked(driver(), listing, afterBuffer, afterBuffer.plusSeconds(3600));
    // Another listing is unaffected.
    booked(
        driver(),
        listings.listing(host().id(), true, "FLEXIBLE", 30),
        start,
        start.plusSeconds(3600));
  }

  @Test
  void payment_expiry_frees_the_slot() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    Instant start = slot(24);
    UUID first = id(booked(driver(), listing, start, start.plusSeconds(3600)));
    book(driver(), "k-" + UUID.randomUUID(), body(listing, start, start.plusSeconds(3600)))
        .andExpect(status().isConflict());

    clock.advance(Duration.ofMinutes(9));
    runTimer(Timer.PAYMENT_EXPIRY);
    assertThat(statusOf(first)).isEqualTo("PENDING_PAYMENT");
    clock.advance(Duration.ofMinutes(1));
    assertThat(runTimer(Timer.PAYMENT_EXPIRY)).isGreaterThanOrEqualTo(1);
    assertThat(statusOf(first)).isEqualTo("EXPIRED");
    assertThat(
            events("BookingExpired", "bookingId", first)
                .get(0)
                .get("payload")
                .get("fromStatus")
                .asText())
        .isEqualTo("PENDING_PAYMENT");

    booked(driver(), listing, start, start.plusSeconds(3600));
    // A late PaymentAuthorized for the expired booking is ignored.
    authorize(first);
    assertThat(statusOf(first)).isEqualTo("EXPIRED");
  }

  @Test
  void instant_book_confirms_on_payment_and_request_to_book_waits_for_the_host() throws Exception {
    User host = host();
    UUID instant = listings.listing(host.id(), true, "FLEXIBLE", 15);
    UUID request = listings.listing(host.id(), false, "FLEXIBLE", 0);
    Instant start = slot(48);
    UUID a = id(booked(driver(), instant, start, start.plusSeconds(3600)));
    UUID b = id(booked(driver(), request, start, start.plusSeconds(3600)));
    authorize(a);
    authorize(b);
    authorize(a); // duplicate signal: ignored
    assertThat(statusOf(a)).isEqualTo("CONFIRMED");
    assertThat(statusOf(b)).isEqualTo("AWAITING_HOST_APPROVAL");
    assertThat(events("BookingConfirmed", "bookingId", a)).hasSize(1);
    assertThat(
            events("BookingConfirmed", "bookingId", a)
                .get(0)
                .get("payload")
                .get("blockedUntil")
                .asText())
        .isEqualTo(start.plusSeconds(3600 + 900).toString());
    JsonNode awaiting = events("BookingAwaitingHostApproval", "bookingId", b).get(0).get("payload");
    assertThat(awaiting.get("hostDecisionDueBy").asText())
        .isEqualTo(clock.instant().plus(Duration.ofHours(24)).toString());

    post("/api/v1/bookings/" + b + "/approve", host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
    post("/api/v1/bookings/" + b + "/approve", host)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/illegal-booking-transition"));
  }

  @Test
  void host_decline_and_decision_timeout() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), false, "STRICT", 0);
    UUID declined = id(booked(driver(), listing, slot(48), slot(49)));
    UUID ignored = id(booked(driver(), listing, slot(72), slot(73)));
    authorize(declined);
    authorize(ignored);
    postJson("/api/v1/bookings/" + declined + "/decline", Map.of("reason", "Car too big"), host)
        .andExpect(jsonPath("$.status").value("DECLINED_BY_HOST"))
        .andExpect(jsonPath("$.refundAmount").value("60.00"));
    assertThat(events("BookingDeclined", "bookingId", declined)).hasSize(1);

    clock.advance(Duration.ofHours(24));
    runTimer(Timer.HOST_DECISION_EXPIRY);
    assertThat(statusOf(ignored)).isEqualTo("EXPIRED");
    assertThat(
            events("BookingExpired", "bookingId", ignored)
                .get(0)
                .get("payload")
                .get("fromStatus")
                .asText())
        .isEqualTo("AWAITING_HOST_APPROVAL");
  }

  @Test
  void cancellation_refunds_follow_the_snapshotted_policy() throws Exception {
    User host = host();
    UUID moderate = listings.listing(host.id(), true, "MODERATE", 0);
    Instant start = slot(48);
    User driver = driver();
    UUID early = id(booked(driver, moderate, start, start.plus(Duration.ofHours(2))));
    UUID late = id(booked(driver, moderate, slot(5), slot(5).plus(Duration.ofHours(2))));
    UUID byHost = id(booked(driver(), moderate, slot(96), slot(96).plus(Duration.ofHours(2))));
    authorize(early);
    authorize(late);
    authorize(byHost);

    postJson("/api/v1/bookings/" + early + "/cancel", Map.of("reason", "Plans changed"), driver)
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_DRIVER"))
        .andExpect(jsonPath("$.refundAmount").value("120.00"));
    postJson("/api/v1/bookings/" + late + "/cancel", Map.of("reason", "Plans changed"), driver)
        .andExpect(jsonPath("$.refundAmount").value("60.00")); // < 24h: 50%
    postJson("/api/v1/bookings/" + byHost + "/cancel", Map.of("reason", "Gate repair"), host)
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_HOST"))
        .andExpect(jsonPath("$.refundAmount").value("120.00"));
    JsonNode cancelled = events("BookingCancelled", "bookingId", late).get(0).get("payload");
    assertThat(cancelled.get("cancelledBy").asText()).isEqualTo("DRIVER");
    assertThat(cancelled.get("refundAmount").asText()).isEqualTo("60.00");
    // The slot is free again.
    booked(driver(), moderate, start, start.plus(Duration.ofHours(2)));
  }

  @Test
  void check_in_window_check_out_no_show_and_auto_completion() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    User driver = driver();
    Instant start = slot(2);
    UUID visit = id(booked(driver, listing, start, start.plus(Duration.ofHours(1))));
    UUID missed =
        id(
            booked(
                driver(),
                listing,
                start.plus(Duration.ofHours(3)),
                start.plus(Duration.ofHours(4))));
    UUID stay =
        id(
            booked(
                driver, listing, start.plus(Duration.ofHours(6)), start.plus(Duration.ofHours(7))));
    authorize(visit);
    authorize(missed);
    authorize(stay);

    post("/api/v1/bookings/" + visit + "/check-in", driver)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/check-in-window"));
    clock.set(start.minus(Duration.ofMinutes(15)));
    post("/api/v1/bookings/" + visit + "/check-in", driver)
        .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    post("/api/v1/bookings/" + visit + "/check-out", driver)
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    JsonNode completed = events("BookingCompleted", "bookingId", visit).get(0).get("payload");
    assertThat(Instant.parse(completed.get("reviewWindowEndsAt").asText()))
        .isEqualTo(clock.instant().plus(Duration.ofDays(14)));

    clock.set(start.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(30)));
    runTimer(Timer.NO_SHOW);
    assertThat(statusOf(missed)).isEqualTo("NO_SHOW");
    assertThat(events("BookingNoShow", "bookingId", missed)).hasSize(1);

    clock.set(start.plus(Duration.ofHours(6)));
    post("/api/v1/bookings/" + stay + "/check-in", driver).andExpect(status().isOk());
    clock.set(start.plus(Duration.ofHours(7)));
    runTimer(Timer.COMPLETION);
    assertThat(statusOf(stay)).isEqualTo("COMPLETED");
  }

  @Test
  void archived_listing_ends_bookings_that_have_not_started() throws Exception {
    UUID listing = listings.listing(host().id(), true, "STRICT", 0);
    UUID pending = id(booked(driver(), listing, slot(24), slot(25)));
    UUID confirmed = id(booked(driver(), listing, slot(48), slot(49)));
    authorize(confirmed);
    saga.listingArchived(UUID.randomUUID(), listing);
    assertThat(statusOf(pending)).isEqualTo("EXPIRED");
    assertThat(statusOf(confirmed)).isEqualTo("CANCELLED_BY_HOST");
    assertThat(
            events("BookingCancelled", "bookingId", confirmed)
                .get(0)
                .get("payload")
                .get("refundAmount")
                .asText())
        .isEqualTo("60.00");
  }

  @Test
  void capture_failure_frees_the_slot() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    UUID b = id(booked(driver(), listing, slot(24), slot(25)));
    authorize(b);
    saga.captureFailed(UUID.randomUUID(), b, driverOf(b), hostOf(b), "card_declined");
    assertThat(statusOf(b)).isEqualTo("PAYMENT_FAILED");
    booked(driver(), listing, slot(24), slot(25));
  }

  @Test
  void visibility_and_lists() throws Exception {
    User host = host();
    User driver = driver();
    UUID listing = listings.listing(host.id(), true, "FLEXIBLE", 0);
    UUID b1 = id(booked(driver, listing, slot(24), slot(25)));
    UUID b2 = id(booked(driver, listing, slot(48), slot(49)));
    id(booked(driver, listing, slot(72), slot(73)));

    get("/api/v1/bookings/" + b1, driver).andExpect(status().isOk());
    get("/api/v1/bookings/" + b1, host).andExpect(status().isOk());
    get("/api/v1/bookings/" + b1, user("ADMIN")).andExpect(status().isOk());
    get("/api/v1/bookings/" + b1, driver()).andExpect(status().isNotFound());

    JsonNode page = body(get("/api/v1/bookings?role=driver&when=upcoming&limit=2", driver));
    assertThat(page.get("items")).hasSize(2);
    assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(b1.toString());
    assertThat(page.get("items").get(1).get("id").asText()).isEqualTo(b2.toString());
    JsonNode next =
        body(
            get(
                "/api/v1/bookings?role=driver&when=upcoming&limit=2&cursor="
                    + page.get("nextCursor").asText(),
                driver));
    assertThat(next.get("items")).hasSize(1);
    assertThat(body(get("/api/v1/bookings?role=host", host)).get("items")).hasSize(3);
    assertThat(body(get("/api/v1/bookings?role=host", driver)).get("items")).isEmpty();
    get("/api/v1/bookings?role=nobody", driver).andExpect(status().isBadRequest());
    get("/api/v1/bookings", driver).andExpect(status().isBadRequest());
  }

  @Test
  void forged_payment_events_do_not_confirm_bookings() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    UUID b = id(booked(driver(), listing, slot(24), slot(25)));
    saga.paymentAuthorized(
        UUID.randomUUID(), b, driverOf(b), hostOf(b), new java.math.BigDecimal("0.01"), "INR");
    saga.paymentAuthorized(
        UUID.randomUUID(),
        b,
        UUID.randomUUID(),
        hostOf(b),
        new java.math.BigDecimal("60.00"),
        "INR");
    saga.paymentFailed(UUID.randomUUID(), b, UUID.randomUUID(), hostOf(b), "x");
    assertThat(statusOf(b)).isEqualTo("PENDING_PAYMENT");
    authorize(b);
    assertThat(statusOf(b)).isEqualTo("CONFIRMED");
  }

  @Test
  void a_driver_holds_at_most_three_unpaid_bookings_and_one_year_ahead() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    User driver = driver();
    for (int i = 0; i < 3; i++) {
      booked(driver, listing, slot(24 + 2 * i), slot(25 + 2 * i));
    }
    book(driver, "k-" + UUID.randomUUID(), body(listing, slot(40), slot(41)))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/too-many-pending-bookings"));
    book(driver(), "k-" + UUID.randomUUID(), body(listing, slot(24 * 400), slot(24 * 400 + 1)))
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/invalid-booking-window"));
  }

  @Test
  void the_host_cannot_decide_after_the_deadline() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), false, "FLEXIBLE", 0);
    UUID b = id(booked(driver(), listing, slot(48), slot(49)));
    authorize(b);
    clock.advance(Duration.ofHours(24));
    post("/api/v1/bookings/" + b + "/approve", host).andExpect(status().isConflict());
    postJson("/api/v1/bookings/" + b + "/decline", Map.of("reason", "late"), host)
        .andExpect(status().isConflict());
  }

  @Test
  void archived_listings_refuse_new_bookings_and_raced_ones_never_confirm() throws Exception {
    UUID listing = listings.listing(host().id(), true, "FLEXIBLE", 0);
    UUID raced = id(booked(driver(), listing, slot(24), slot(25)));
    // The archive was processed, then a booking that read the old quote got in.
    jdbc.sql("INSERT INTO archived_listings (listing_id, archived_at) VALUES (:l, now())")
        .param("l", listing)
        .update();
    book(driver(), "k-" + UUID.randomUUID(), body(listing, slot(48), slot(49)))
        .andExpect(status().isNotFound());
    authorize(raced);
    assertThat(statusOf(raced)).isEqualTo("EXPIRED");
  }

  @Test
  void reasons_reject_invisible_characters() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), true, "FLEXIBLE", 0);
    UUID b = id(booked(driver(), listing, slot(24), slot(25)));
    authorize(b);
    postJson("/api/v1/bookings/" + b + "/cancel", Map.of("reason", "<b>x</b>‮"), host)
        .andExpect(status().isBadRequest());
  }
}
