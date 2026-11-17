package com.parksmart.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Submit, list, summary and moderation over HTTP; eligibility is seeded as the consumer would. */
class ReviewIT extends IntegrationTest {

  record Booking(UUID id, UUID listingId, User driver, User host) {}

  private Booking completedBooking(Instant windowEnds) {
    User driver = driver();
    User host = host();
    Booking b = new Booking(UUID.randomUUID(), UUID.randomUUID(), driver, host);
    jdbc.sql(
            """
            INSERT INTO review_eligibility
              (booking_id, driver_account_id, host_account_id, listing_id, completed_at, expires_at)
            VALUES (:b, :d, :h, :l, :c, :e)
            """)
        .param("b", b.id())
        .param("d", driver.id())
        .param("h", host.id())
        .param("l", b.listingId())
        .param("c", java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)))
        .param("e", java.sql.Timestamp.from(windowEnds))
        .update();
    return b;
  }

  private Booking open() {
    return completedBooking(Instant.now().plus(10, ChronoUnit.DAYS));
  }

  @Test
  void driver_reviews_the_listing_and_host_reviews_the_driver() throws Exception {
    Booking b = open();
    var driverReview =
        postJson(
            "/api/v1/reviews",
            Map.of("bookingId", b.id(), "rating", 5, "comment", "Easy"),
            b.driver());
    driverReview
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.direction").value("DRIVER_TO_LISTING"))
        .andExpect(jsonPath("$.targetType").value("LISTING"))
        .andExpect(jsonPath("$.targetId").value(b.listingId().toString()));

    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 4), b.host())
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.direction").value("HOST_TO_DRIVER"))
        .andExpect(jsonPath("$.targetId").value(b.driver().id().toString()));

    get("/api/v1/reviews/summary?targetType=LISTING&targetId=" + b.listingId(), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewCount").value(1))
        .andExpect(jsonPath("$.avgRating").value("5.00"));
    assertThat(events("RatingAggregateUpdated", "targetId", b.listingId())).hasSize(1);
    assertThat(events("ReviewSubmitted", "bookingId", b.id())).hasSize(2);
  }

  @Test
  void second_review_in_the_same_direction_is_409() throws Exception {
    Booking b = open();
    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 3), b.driver())
        .andExpect(status().isCreated());
    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 1), b.driver())
        .andExpect(status().isConflict());
  }

  @Test
  void a_stranger_gets_403_and_an_unknown_booking_404() throws Exception {
    Booking b = open();
    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 5), driver())
        .andExpect(status().isForbidden());
    postJson("/api/v1/reviews", Map.of("bookingId", UUID.randomUUID(), "rating", 5), b.driver())
        .andExpect(status().isNotFound());
  }

  @Test
  void driver_reviews_need_a_signed_in_caller() throws Exception {
    UUID driverId = UUID.randomUUID();
    get("/api/v1/reviews?targetType=DRIVER&targetId=" + driverId, null)
        .andExpect(status().isUnauthorized());
    get("/api/v1/reviews/summary?targetType=DRIVER&targetId=" + driverId, null)
        .andExpect(status().isUnauthorized());
    get("/api/v1/reviews?targetType=DRIVER&targetId=" + driverId, host())
        .andExpect(status().isOk());
  }

  @Test
  void a_closed_window_is_422() throws Exception {
    Booking b = completedBooking(Instant.now().minus(1, ChronoUnit.HOURS));
    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 5), b.driver())
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void validation_and_authentication() throws Exception {
    Booking b = open();
    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 6), b.driver())
        .andExpect(status().isBadRequest());
    postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 5), null)
        .andExpect(status().isUnauthorized());
    get("/api/v1/reviews?targetType=NOPE&targetId=" + UUID.randomUUID(), null)
        .andExpect(status().isBadRequest());
  }

  @Test
  void hiding_needs_admin_and_updates_the_aggregate() throws Exception {
    Booking b = open();
    String id =
        body(postJson("/api/v1/reviews", Map.of("bookingId", b.id(), "rating", 2), b.driver()))
            .get("id")
            .asText();
    String path = "/admin/v1/reviews/" + id + "/hide";
    postJson(path, Map.of("reason", "abuse"), b.driver()).andExpect(status().isForbidden());
    postJson(path, Map.of("reason", "abuse"), user("ADMIN")).andExpect(status().isOk());
    postJson(path, Map.of("reason", "abuse"), user("ADMIN")).andExpect(status().isOk());

    get("/api/v1/reviews?targetType=LISTING&targetId=" + b.listingId(), null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    get("/api/v1/reviews/summary?targetType=LISTING&targetId=" + b.listingId(), null)
        .andExpect(jsonPath("$.reviewCount").value(0))
        .andExpect(jsonPath("$.avgRating").isEmpty());
    postJson(
            "/admin/v1/reviews/" + UUID.randomUUID() + "/hide",
            Map.of("reason", "x"),
            user("ADMIN"))
        .andExpect(status().isNotFound());
  }

  @Test
  void listing_pages_newest_first() throws Exception {
    UUID listing = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      User d = driver();
      User h = host();
      UUID booking = UUID.randomUUID();
      jdbc.sql(
              """
              INSERT INTO review_eligibility
                (booking_id, driver_account_id, host_account_id, listing_id, completed_at, expires_at)
              VALUES (:b, :d, :h, :l, now(), now() + interval '5 days')
              """)
          .param("b", booking)
          .param("d", d.id())
          .param("h", h.id())
          .param("l", listing)
          .update();
      postJson("/api/v1/reviews", Map.of("bookingId", booking, "rating", 3 + i % 3), d)
          .andExpect(status().isCreated());
    }
    var first = body(get("/api/v1/reviews?targetType=LISTING&limit=2&targetId=" + listing, null));
    assertThat(first.get("items")).hasSize(2);
    String cursor = first.get("nextCursor").asText();
    var second =
        body(
            get(
                "/api/v1/reviews?targetType=LISTING&limit=2&targetId="
                    + listing
                    + "&cursor="
                    + cursor,
                null));
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
  }
}
