package com.parksmart.listing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Hard rule 7: every mutating endpoint returns 403 to anyone but the listing's host. */
class OwnershipIT extends ListingFixtures {

  @Test
  void other_hosts_and_admins_get_403_on_every_mutation() throws Exception {
    User owner = host();
    UUID id = published(owner);
    UUID photo =
        UUID.fromString(
            body(get("/api/v1/listings/" + id, owner)).get("photos").get(0).get("id").asText());
    String etag = etag(id, owner);
    String base = "/api/v1/listings/" + id;

    for (User stranger : List.of(host(), user("ADMIN"), driver())) {
      mvc.perform(
              patch(base)
                  .header("Authorization", "Bearer " + stranger.token())
                  .header("If-Match", etag)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"title\":\"Hijacked listing\"}"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/forbidden"));
      delete(base, stranger).andExpect(status().isForbidden());
      post(base + "/submit", stranger).andExpect(status().isForbidden());
      post(base + "/pause", stranger).andExpect(status().isForbidden());
      post(base + "/resume", stranger).andExpect(status().isForbidden());
      postJson(
              base + "/photos/upload-urls",
              Map.of("contentType", "image/png", "contentLength", 10),
              stranger)
          .andExpect(status().isForbidden());
      post(base + "/photos/" + photo + "/confirm", stranger).andExpect(status().isForbidden());
      delete(base + "/photos/" + photo, stranger).andExpect(status().isForbidden());
      putJson(base + "/photos/order", Map.of("photoIds", List.of(photo)), stranger)
          .andExpect(status().isForbidden());
      putJson(base + "/availability", allWeek(), stranger).andExpect(status().isForbidden());
      postJson(
              base + "/blackouts",
              Map.of("start", "2031-01-01T00:00:00Z", "end", "2031-01-02T00:00:00Z"),
              stranger)
          .andExpect(status().isForbidden());
      delete(base + "/blackouts/" + UUID.randomUUID(), stranger).andExpect(status().isForbidden());
    }

    // Nothing changed.
    get(base, owner)
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.title").value("Covered spot near Baner"))
        .andExpect(jsonPath("$.photos.length()").value(1));
  }

  @Test
  void admin_endpoints_need_admin() throws Exception {
    UUID id = create(host());
    User host = host();
    post("/admin/v1/listings/" + id + "/approve", host).andExpect(status().isForbidden());
    postJson("/admin/v1/listings/" + id + "/reject", Map.of("reason", "no"), driver())
        .andExpect(status().isForbidden());
    post("/admin/v1/listings/" + id + "/approve", null).andExpect(status().isUnauthorized());
    post("/admin/v1/listings/" + id + "/approve", user("ADMIN"))
        .andExpect(status().isConflict()); // still DRAFT
  }

  @Test
  void archive_is_refused_while_a_booking_blocks_the_listing() throws Exception {
    User host = host();
    UUID id = published(host);
    UUID booking = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO listing_active_bookings (booking_id, listing_id, blocked_until, updated_at)
            VALUES (:b, :l, now() + interval '1 day', now())
            """)
        .param("b", booking)
        .param("l", id)
        .update();
    delete("/api/v1/listings/" + id, host)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/listing-has-active-bookings"));
    jdbc.sql("UPDATE listing_active_bookings SET ended_at = now() WHERE booking_id = :b")
        .param("b", booking)
        .update();
    delete("/api/v1/listings/" + id, host).andExpect(status().isNoContent());
  }

  @Test
  void an_admin_cannot_moderate_their_own_listing() throws Exception {
    User hostAdmin = user("DRIVER", "HOST", "ADMIN");
    eligible(hostAdmin);
    UUID id = create(hostAdmin);
    addPhoto(id, hostAdmin);
    putJson("/api/v1/listings/" + id + "/availability", allWeek(), hostAdmin)
        .andExpect(status().isOk());
    post("/api/v1/listings/" + id + "/submit", hostAdmin).andExpect(status().isOk());
    post("/admin/v1/listings/" + id + "/approve", hostAdmin).andExpect(status().isForbidden());
    postJson("/admin/v1/listings/" + id + "/reject", Map.of("reason", "x"), hostAdmin)
        .andExpect(status().isForbidden());
    post("/admin/v1/listings/" + id + "/approve", user("ADMIN")).andExpect(status().isOk());
  }
}
