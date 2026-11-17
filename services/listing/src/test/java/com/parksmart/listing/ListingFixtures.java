package com.parksmart.listing;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Helpers shared by the listing integration tests: valid bodies and a publishable listing. */
abstract class ListingFixtures extends IntegrationTest {

  static Map<String, Object> createBody() {
    Map<String, Object> body = new HashMap<>();
    body.put("title", "Covered spot near Baner");
    body.put("description", "Gated society, guard 24x7");
    body.put("address", "12 Baner Road, Pune");
    body.put("location", Map.of("lat", 18.559, "lng", 73.786));
    body.put("timeZone", "Asia/Kolkata");
    body.put("pricePerHour", "60.00");
    body.put("currency", "INR");
    body.put("maxVehicleType", "SUV");
    body.put("amenities", List.of("COVERED", "CCTV"));
    body.put("instantBook", true);
    body.put("cancellationPolicy", "MODERATE");
    body.put("bufferMinutes", 15);
    return body;
  }

  /** Every day 00:00-24:00. */
  static Map<String, Object> allWeek() {
    List<Map<String, String>> rules =
        java.util.Arrays.stream(java.time.DayOfWeek.values())
            .map(d -> Map.of("dayOfWeek", d.name(), "startTime", "00:00", "endTime", "24:00"))
            .toList();
    return Map.of("rules", rules);
  }

  /** The host passes Listing's eligibility projection (as if the events had arrived). */
  void eligible(User host) {
    jdbc.sql(
            """
            INSERT INTO host_eligibility
              (host_account_id, phone_verified, phone_changed_at, has_profile_photo,
               photo_changed_at, updated_at)
            VALUES (:id, true, now(), true, now(), now())
            ON CONFLICT (host_account_id) DO UPDATE SET phone_verified = true, has_profile_photo = true
            """)
        .param("id", host.id())
        .update();
  }

  UUID create(User host) throws Exception {
    return UUID.fromString(
        body(postJson("/api/v1/listings", createBody(), host).andExpect(status().isCreated()))
            .get("id")
            .asText());
  }

  String etag(UUID listingId, User user) throws Exception {
    return mvc.perform(
            MockMvcRequestBuilders.get("/api/v1/listings/" + listingId)
                .header("Authorization", "Bearer " + user.token()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getHeader("ETag");
  }

  /** Uploads and confirms one PNG photo; returns its id. */
  UUID addPhoto(UUID listingId, User host) throws Exception {
    JsonNode slot =
        body(
            postJson(
                    "/api/v1/listings/" + listingId + "/photos/upload-urls",
                    Map.of("contentType", "image/png", "contentLength", 200),
                    host)
                .andExpect(status().isCreated()));
    UUID photoId = UUID.fromString(slot.get("photoId").asText());
    storage.put(
        "uploads/listings/" + listingId + "/" + photoId, "image/png", FakePhotoStorage.png(200));
    post("/api/v1/listings/" + listingId + "/photos/" + photoId + "/confirm", host)
        .andExpect(status().isOk());
    return photoId;
  }

  /** Created, photo, rules, submitted, approved: PUBLISHED. */
  UUID published(User host) throws Exception {
    eligible(host);
    UUID id = create(host);
    addPhoto(id, host);
    putJson("/api/v1/listings/" + id + "/availability", allWeek(), host).andExpect(status().isOk());
    post("/api/v1/listings/" + id + "/submit", host).andExpect(status().isOk());
    post("/admin/v1/listings/" + id + "/approve", admin()).andExpect(status().isOk());
    return id;
  }

  static User admin() {
    return user("ADMIN");
  }
}
