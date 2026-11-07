package com.parksmart.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ListingLifecycleIT extends ListingFixtures {

  private ResultActions patchJson(UUID id, Object body, String ifMatch, User user)
      throws Exception {
    return mvc.perform(
        patch("/api/v1/listings/" + id)
            .header("Authorization", "Bearer " + user.token())
            .header("If-Match", ifMatch)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  @Test
  void host_creates_a_draft_with_etag_and_location() throws Exception {
    User host = host();
    postJson("/api/v1/listings", createBody(), host)
        .andExpect(status().isCreated())
        .andExpect(header().string("ETag", "\"0\""))
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/listings/")))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.hostAccountId").value(host.id().toString()))
        .andExpect(jsonPath("$.pricePerHour").value("60.00"))
        .andExpect(jsonPath("$.location.lat").value(18.559))
        .andExpect(jsonPath("$.amenities[0]").value("COVERED"));
    UUID id =
        UUID.fromString(
            events("ListingCreated", "hostAccountId", host.id())
                .get(0)
                .get("payload")
                .get("listingId")
                .asText());
    Double lat =
        jdbc.sql("SELECT ST_Y(location::geometry) FROM parking_listings WHERE id = :id")
            .param("id", id)
            .query(Double.class)
            .single();
    assertThat(lat).isEqualTo(18.559);
  }

  @Test
  void only_hosts_create_and_bad_input_is_400() throws Exception {
    postJson("/api/v1/listings", createBody(), driver()).andExpect(status().isForbidden());
    Map<String, Object> body = createBody();
    body.put("pricePerHour", "2000.01");
    postJson("/api/v1/listings", body, host())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-pricePerHour"));
    body = createBody();
    body.put("timeZone", "Mars/Olympus");
    postJson("/api/v1/listings", body, host())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-timeZone"));
    body = createBody();
    body.put("bufferMinutes", 7);
    postJson("/api/v1/listings", body, host()).andExpect(status().isBadRequest());
    body = createBody();
    body.remove("title");
    postJson("/api/v1/listings", body, host())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("title"));
  }

  @Test
  void drafts_are_private_and_published_listings_public() throws Exception {
    User host = host();
    UUID draft = create(host);
    get("/api/v1/listings/" + draft, null).andExpect(status().isNotFound());
    get("/api/v1/listings/" + draft, driver()).andExpect(status().isNotFound());
    get("/api/v1/listings/" + draft, host).andExpect(status().isOk());
    get("/api/v1/listings/" + draft, admin()).andExpect(status().isOk());

    UUID live = published(host());
    get("/api/v1/listings/" + live, null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.photos.length()").value(1));
  }

  @Test
  void full_lifecycle_emits_the_right_events() throws Exception {
    User host = host();
    UUID id = published(host);

    assertThat(events("ListingSubmittedForReview", "listingId", id)).hasSize(1);
    List<JsonNode> published =
        jdbc
            .sql(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'ListingPublished'"
                    + " AND payload->'payload'->'listing'->>'listingId' = :id")
            .param("id", id.toString())
            .query(String.class)
            .list()
            .stream()
            .map(s -> {
              try {
                return json.readTree(s);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
            .toList();
    assertThat(published).hasSize(1);
    JsonNode snapshot = published.get(0).get("payload").get("listing");
    assertThat(snapshot.get("status").asText()).isEqualTo("PUBLISHED");
    assertThat(snapshot.get("thumbnailUrl").asText()).contains("listings/" + id + "/photo-");
    assertThat(snapshot.get("availabilityRules")).hasSize(7);
    assertThat(snapshot.get("pricePerHour").asText()).isEqualTo("60.00");

    post("/api/v1/listings/" + id + "/pause", host).andExpect(jsonPath("$.status").value("PAUSED"));
    assertThat(events("ListingPaused", "listingId", id)).hasSize(1);
    post("/api/v1/listings/" + id + "/pause", host).andExpect(status().isConflict());
    post("/api/v1/listings/" + id + "/resume", host)
        .andExpect(jsonPath("$.status").value("PUBLISHED"));

    delete("/api/v1/listings/" + id, host).andExpect(status().isNoContent());
    assertThat(events("ListingArchived", "listingId", id)).hasSize(1);
    get("/api/v1/listings/" + id, null).andExpect(status().isNotFound());
    get("/api/v1/listings/" + id, host).andExpect(jsonPath("$.status").value("ARCHIVED"));
    post("/api/v1/listings/" + id + "/resume", host)
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/illegal-transition"));
  }

  @Test
  void submit_lists_every_unmet_requirement() throws Exception {
    User host = host();
    UUID id = create(host);
    post("/api/v1/listings/" + id + "/submit", host)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/listing-not-submittable"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("photo"),
                        org.hamcrest.Matchers.containsString("availability rule"),
                        org.hamcrest.Matchers.containsString("verified phone"),
                        org.hamcrest.Matchers.containsString("profile photo"))));
  }

  @Test
  void admin_rejects_with_reason_and_host_resubmits() throws Exception {
    User host = host();
    eligible(host);
    UUID id = create(host);
    addPhoto(id, host);
    putJson("/api/v1/listings/" + id + "/availability", allWeek(), host).andExpect(status().isOk());
    post("/api/v1/listings/" + id + "/submit", host).andExpect(status().isOk());

    get("/admin/v1/listings?status=PENDING_REVIEW&limit=100", admin())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.id == '" + id + "')]").exists());
    get("/admin/v1/listings?status=PENDING_REVIEW", host).andExpect(status().isForbidden());
    postJson("/admin/v1/listings/" + id + "/reject", Map.of("reason", "Blurry photo"), host)
        .andExpect(status().isForbidden());
    postJson("/admin/v1/listings/" + id + "/reject", Map.of(), admin())
        .andExpect(status().isBadRequest());

    postJson("/admin/v1/listings/" + id + "/reject", Map.of("reason", "Blurry photo"), admin())
        .andExpect(jsonPath("$.status").value("REJECTED"));
    get("/api/v1/listings/" + id, host)
        .andExpect(jsonPath("$.rejectionReason").value("Blurry photo"));
    assertThat(
            events("ListingRejected", "listingId", id).get(0).get("payload").get("reason").asText())
        .isEqualTo("Blurry photo");
    Long logRows =
        jdbc.sql("SELECT count(*) FROM listing_moderation_log WHERE listing_id = :id")
            .param("id", id)
            .query(Long.class)
            .single();
    assertThat(logRows).isEqualTo(1);

    post("/api/v1/listings/" + id + "/submit", host)
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    post("/admin/v1/listings/" + id + "/approve", admin())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
  }

  @Test
  void patch_needs_a_current_etag_and_relocation_sends_back_to_review() throws Exception {
    User host = host();
    UUID id = published(host);
    String etag = etag(id, host);

    patchJson(id, Map.of("pricePerHour", "75.50"), "\"999\"", host)
        .andExpect(status().isPreconditionFailed());
    String next =
        patchJson(id, Map.of("pricePerHour", "75.50"), etag, host)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.pricePerHour").value("75.50"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    assertThat(next).isNotEqualTo(etag);
    assertThat(events("ListingUpdated", "listingId", id)).isEmpty(); // snapshot nests the id
    Long updated =
        jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'ListingUpdated'"
                    + " AND payload->'payload'->'listing'->>'listingId' = :id")
            .param("id", id.toString())
            .query(Long.class)
            .single();
    assertThat(updated).isEqualTo(1);

    // Stale ETag after the change.
    patchJson(id, Map.of("title", "Another title"), etag, host)
        .andExpect(status().isPreconditionFailed());

    patchJson(id, Map.of("address", "99 New Street, Pune"), next, host)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    patchJson(id, Map.of("title", "Edit during review"), etag(id, host), host)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/listing-not-editable"));
    patchJson(id, Map.of(), etag(id, host), host).andExpect(status().isBadRequest());
  }

  @Test
  void hosts_page_through_their_own_listings() throws Exception {
    User host = host();
    for (int i = 0; i < 3; i++) {
      create(host);
    }
    create(host()); // someone else's
    JsonNode first =
        body(get("/api/v1/hosts/me/listings?limit=2", host).andExpect(status().isOk()));
    assertThat(first.get("items")).hasSize(2);
    String cursor = first.get("nextCursor").asText();
    JsonNode second = body(get("/api/v1/hosts/me/listings?limit=2&cursor=" + cursor, host));
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("nextCursor").isNull()).isTrue();
    get("/api/v1/hosts/me/listings?cursor=garbage", host).andExpect(status().isBadRequest());
  }

  @Test
  void suspended_hosts_cannot_be_approved() throws Exception {
    User host = host();
    eligible(host);
    UUID id = create(host);
    addPhoto(id, host);
    putJson("/api/v1/listings/" + id + "/availability", allWeek(), host).andExpect(status().isOk());
    post("/api/v1/listings/" + id + "/submit", host).andExpect(status().isOk());
    jdbc.sql("UPDATE host_eligibility SET suspended = true WHERE host_account_id = :id")
        .param("id", host.id())
        .update();
    post("/admin/v1/listings/" + id + "/approve", admin())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/host-suspended"));
  }
}
