package com.parksmart.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PhotosAndAvailabilityIT extends ListingFixtures {

  private JsonNode slot(UUID listingId, User host) throws Exception {
    return body(
        postJson(
                "/api/v1/listings/" + listingId + "/photos/upload-urls",
                Map.of("contentType", "image/png", "contentLength", 100),
                host)
            .andExpect(status().isCreated()));
  }

  private long outboxCount(String type, UUID listingId) {
    return jdbc.sql(
            """
            SELECT count(*) FROM outbox_events WHERE event_type = :type
              AND (payload->'payload'->>'listingId' = :id
                   OR payload->'payload'->'listing'->>'listingId' = :id)
            """)
        .param("type", type)
        .param("id", listingId.toString())
        .query(Long.class)
        .single();
  }

  @Test
  void confirm_copies_to_a_public_key_and_verifies_the_copy() throws Exception {
    User host = host();
    UUID id = create(host);
    JsonNode slot = slot(id, host);
    String photoId = slot.get("photoId").asText();
    String staging = "uploads/listings/" + id + "/" + photoId;
    String confirm = "/api/v1/listings/" + id + "/photos/" + photoId + "/confirm";

    post(confirm, host)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/upload-missing"));
    storage.put(staging, "image/png", new byte[100]); // not a PNG
    post(confirm, host)
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/upload-mismatch"));
    assertThat(storage.exists("listings/" + id + "/photo-" + photoId)).isFalse();

    storage.put(staging, "image/png", FakePhotoStorage.png(100));
    post(confirm, host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(
            jsonPath("$.url")
                .value("https://photos.parksmart.test/listings/" + id + "/photo-" + photoId));
    assertThat(storage.deleted()).contains(staging);
    post(confirm, host)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/photo-already-confirmed"));
  }

  @Test
  void ten_photos_at_most_and_reorder_must_list_every_ready_photo() throws Exception {
    User host = host();
    UUID id = create(host);
    UUID first = addPhoto(id, host);
    UUID second = addPhoto(id, host);
    for (int i = 0; i < 8; i++) {
      slot(id, host);
    }
    postJson(
            "/api/v1/listings/" + id + "/photos/upload-urls",
            Map.of("contentType", "image/png", "contentLength", 100),
            host)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/photo-limit"));

    putJson("/api/v1/listings/" + id + "/photos/order", Map.of("photoIds", List.of(second)), host)
        .andExpect(status().isBadRequest());
    putJson(
            "/api/v1/listings/" + id + "/photos/order",
            Map.of("photoIds", List.of(second, first)),
            host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photos[0].id").value(second.toString()))
        .andExpect(jsonPath("$.photos[1].id").value(first.toString()));
  }

  @Test
  void the_last_photo_of_a_published_listing_cannot_be_removed() throws Exception {
    User host = host();
    UUID id = published(host);
    UUID photo =
        UUID.fromString(
            body(get("/api/v1/listings/" + id, host)).get("photos").get(0).get("id").asText());
    delete("/api/v1/listings/" + id + "/photos/" + photo, host)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/last-photo"));
    UUID extra = addPhoto(id, host);
    assertThat(outboxCount("ListingUpdated", id)).isEqualTo(1); // thumbnail list changed
    delete("/api/v1/listings/" + id + "/photos/" + photo, host).andExpect(status().isNoContent());
    assertThat(storage.deleted()).contains("listings/" + id + "/photo-" + photo);
    get("/api/v1/listings/" + id, null)
        .andExpect(jsonPath("$.photos.length()").value(1))
        .andExpect(jsonPath("$.photos[0].id").value(extra.toString()));
  }

  @Test
  void overlapping_rules_are_rejected_and_drafts_emit_no_availability_events() throws Exception {
    User host = host();
    UUID id = create(host);
    Map<String, Object> overlap =
        Map.of(
            "rules",
            List.of(
                Map.of("dayOfWeek", "MONDAY", "startTime", "08:00", "endTime", "12:00"),
                Map.of("dayOfWeek", "MONDAY", "startTime", "11:00", "endTime", "14:00")));
    putJson("/api/v1/listings/" + id + "/availability", overlap, host)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-availability"));
    putJson(
            "/api/v1/listings/" + id + "/availability",
            Map.of(
                "rules",
                List.of(Map.of("dayOfWeek", "MONDAY", "startTime", "10:00", "endTime", "09:00"))),
            host)
        .andExpect(status().isBadRequest());
    putJson("/api/v1/listings/" + id + "/availability", allWeek(), host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.availabilityRules.length()").value(7))
        .andExpect(jsonPath("$.availabilityRules[0].endTime").value("24:00"));
    assertThat(outboxCount("AvailabilityChanged", id)).isZero();
  }

  @Test
  void blackouts_on_a_live_listing_emit_availability_changed() throws Exception {
    User host = host();
    UUID id = published(host);
    Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    JsonNode blackout =
        body(
            postJson(
                    "/api/v1/listings/" + id + "/blackouts",
                    Map.of(
                        "start",
                        start.toString(),
                        "end",
                        start.plus(1, ChronoUnit.DAYS).toString(),
                        "reason",
                        "Family visiting"),
                    host)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("Family visiting")));
    assertThat(outboxCount("AvailabilityChanged", id)).isEqualTo(1);
    // The public view hides the reason.
    get("/api/v1/listings/" + id, null)
        .andExpect(jsonPath("$.blackouts[0].id").value(blackout.get("id").asText()))
        .andExpect(jsonPath("$.blackouts[0].reason").isEmpty());

    postJson(
            "/api/v1/listings/" + id + "/blackouts",
            Map.of("start", "2020-01-01T00:00:00Z", "end", "2020-01-02T00:00:00Z"),
            host)
        .andExpect(status().isBadRequest());
    delete("/api/v1/listings/" + id + "/blackouts/" + blackout.get("id").asText(), host)
        .andExpect(status().isNoContent());
    delete("/api/v1/listings/" + id + "/blackouts/" + UUID.randomUUID(), host)
        .andExpect(status().isNotFound());
    assertThat(outboxCount("AvailabilityChanged", id)).isEqualTo(2);
  }

  @Test
  void booking_quote_checks_status_rules_and_blackouts_in_the_listing_time_zone() throws Exception {
    User host = host();
    UUID id = published(host);
    // Mondays 08:00-12:00 and 12:00-20:00 local (Asia/Kolkata, UTC+5:30): adjacent rules join.
    putJson(
            "/api/v1/listings/" + id + "/availability",
            Map.of(
                "rules",
                List.of(
                    Map.of("dayOfWeek", "MONDAY", "startTime", "08:00", "endTime", "12:00"),
                    Map.of("dayOfWeek", "MONDAY", "startTime", "12:00", "endTime", "20:00"))),
            host)
        .andExpect(status().isOk());
    User driver = driver();
    String base = "/internal/v1/listings/" + id + "/booking-quote";
    // Monday 2030-01-07 10:00-14:00 IST = 04:30-08:30 UTC.
    get(base + "?start=2030-01-07T04:30:00Z&end=2030-01-07T08:30:00Z", driver)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bookable").value(true))
        .andExpect(jsonPath("$.unbookableReason").isEmpty())
        .andExpect(jsonPath("$.hostAccountId").value(host.id().toString()))
        .andExpect(jsonPath("$.pricePerHour").value("60.00"))
        .andExpect(jsonPath("$.bufferMinutes").value(15))
        .andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));
    // 07:00 IST is before the rule opens.
    get(base + "?start=2030-01-07T01:30:00Z&end=2030-01-07T04:30:00Z", driver)
        .andExpect(jsonPath("$.unbookableReason").value("OUTSIDE_AVAILABILITY"));
    // A Tuesday has no rule.
    get(base + "?start=2030-01-08T04:30:00Z&end=2030-01-08T05:30:00Z", driver)
        .andExpect(jsonPath("$.bookable").value(false));

    postJson(
            "/api/v1/listings/" + id + "/blackouts",
            Map.of("start", "2030-01-07T06:00:00Z", "end", "2030-01-07T07:00:00Z"),
            host)
        .andExpect(status().isCreated());
    get(base + "?start=2030-01-07T04:30:00Z&end=2030-01-07T08:30:00Z", driver)
        .andExpect(jsonPath("$.unbookableReason").value("BLACKED_OUT"));

    post("/api/v1/listings/" + id + "/pause", host).andExpect(status().isOk());
    get(base + "?start=2030-01-07T04:30:00Z&end=2030-01-07T08:30:00Z", driver)
        .andExpect(jsonPath("$.unbookableReason").value("NOT_PUBLISHED"));

    get(base + "?start=2030-01-07T08:30:00Z&end=2030-01-07T04:30:00Z", driver)
        .andExpect(status().isBadRequest());
    get(base + "?start=2030-01-07T04:30:00Z&end=2030-01-07T08:30:00Z", user("ADMIN"))
        .andExpect(status().isForbidden());
    get(base + "?start=2030-01-07T04:30:00Z&end=2030-01-07T08:30:00Z", null)
        .andExpect(status().isUnauthorized());
  }

  @Test
  void a_multi_day_booking_needs_round_the_clock_rules() throws Exception {
    UUID id = published(host()); // all week 00:00-24:00
    get(
            "/internal/v1/listings/"
                + id
                + "/booking-quote?start=2030-01-07T10:00:00Z&end=2030-01-10T10:00:00Z",
            driver())
        .andExpect(jsonPath("$.bookable").value(true));
  }
}
