package com.parksmart.adminbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class ModerationIT extends IntegrationTest {

  static final String QUEUE = "/admin/v1/listings?status=PENDING_REVIEW&limit=";

  @Test
  void queue_is_composed_from_listing_and_profile_with_the_admin_token_forwarded()
      throws Exception {
    UUID host = UUID.randomUUID();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    STUB.on(
        "GET",
        QUEUE + "20",
        200,
        Fixtures.page("next-1", Fixtures.summary(a, "Spot A"), Fixtures.summary(b, "Spot B")));
    STUB.on("GET", "/api/v1/listings/" + a, 200, Fixtures.listing(a, host, "PENDING_REVIEW"));
    STUB.on("GET", "/api/v1/listings/" + b, 200, Fixtures.listing(b, host, "PENDING_REVIEW"));
    STUB.on("GET", "/api/v1/profiles/" + host, 200, Fixtures.profile(host, "Asha"));
    User admin = admin();

    var result =
        perform(
                MockMvcRequestBuilders.get("/admin/v1/moderation/listings")
                    .header("X-Correlation-Id", "corr-123"),
                admin)
            .andExpect(status().isOk())
            .andExpect(header().string("X-Correlation-Id", "corr-123"))
            .andExpect(jsonPath("$.nextCursor").value("next-1"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].listingId").value(a.toString()))
            .andExpect(jsonPath("$.items[0].title").value("Spot A"))
            .andExpect(jsonPath("$.items[0].pricePerHour").value("60.00"))
            .andExpect(jsonPath("$.items[0].hostAccountId").value(host.toString()))
            .andExpect(jsonPath("$.items[0].address").value("12 MG Road, Pune"))
            .andExpect(jsonPath("$.items[0].photoCount").value(2))
            .andExpect(jsonPath("$.items[0].availabilityRuleCount").value(1))
            .andExpect(jsonPath("$.items[0].lastChangedAt").value("2030-01-02T09:00:00Z"))
            .andExpect(jsonPath("$.items[0].host.displayName").value("Asha"))
            .andExpect(jsonPath("$.items[0].partial").value(false))
            .andExpect(jsonPath("$.items[1].host.displayName").value("Asha"));

    String text = result.andReturn().getResponse().getContentAsString();
    assertThat(text).doesNotContain("leak@example.com").doesNotContain("+919999999999");
    // One profile lookup per host, not per listing.
    assertThat(STUB.calls("GET", "/api/v1/profiles/")).hasSize(1);
    assertThat(STUB.received)
        .allSatisfy(r -> {
          assertThat(r.header("Authorization")).isEqualTo("Bearer " + admin.token());
          assertThat(r.header("X-Correlation-Id")).isEqualTo("corr-123");
        });
  }

  @Test
  void a_failing_detail_lookup_makes_only_that_item_partial() throws Exception {
    UUID host = UUID.randomUUID();
    UUID ok = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    STUB.on(
        "GET",
        QUEUE + "5&cursor=abc",
        200,
        Fixtures.page(null, Fixtures.summary(ok, "Fine"), Fixtures.summary(broken, "Broken")));
    STUB.on("GET", "/api/v1/listings/" + ok, 200, Fixtures.listing(ok, host, "PENDING_REVIEW"));
    STUB.on("GET", "/api/v1/listings/" + broken, 500, "{\"detail\":\"NPE at Foo.java:12\"}");
    STUB.on("GET", "/api/v1/profiles/" + host, 503, "");

    JsonNode body =
        body(
            get("/admin/v1/moderation/listings?limit=5&cursor=abc", admin())
                .andExpect(status().isOk()));
    assertThat(body.at("/items/0/partial").asBoolean()).isTrue(); // profile failed
    assertThat(body.at("/items/0/address").asText()).isEqualTo("12 MG Road, Pune");
    assertThat(body.at("/items/0/host").isNull()).isTrue();
    assertThat(body.at("/items/1/partial").asBoolean()).isTrue();
    assertThat(body.at("/items/1/address").isNull()).isTrue();
    assertThat(body.at("/items/1/title").asText()).isEqualTo("Broken");
    assertThat(body.at("/nextCursor").isNull()).isTrue();
    assertThat(body.toString()).doesNotContain("Foo.java");
  }

  @Test
  void queue_fails_with_503_when_listing_is_down_and_validates_paging() throws Exception {
    STUB.on("GET", QUEUE + "20", 500, "{\"detail\":\"db password wrong\"}");
    get("/admin/v1/moderation/listings", admin())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/listing-unavailable"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));

    get("/admin/v1/moderation/listings?limit=51", admin())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/validation-failed"));
    get("/admin/v1/moderation/listings?limit=0", admin()).andExpect(status().isBadRequest());
    get("/admin/v1/moderation/listings?cursor=" + "x".repeat(513), admin())
        .andExpect(status().isBadRequest());
  }

  @Test
  void approve_forwards_and_returns_the_outcome() throws Exception {
    UUID listing = UUID.randomUUID();
    UUID host = UUID.randomUUID();
    STUB.on(
        "POST",
        "/admin/v1/listings/" + listing + "/approve",
        200,
        Fixtures.listing(listing, host, "PUBLISHED"));

    postJson(
            "/admin/v1/moderation/listings/" + listing + "/approve", "{\"reason\":\"ok\"}", admin())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.listingId").value(listing.toString()))
        .andExpect(jsonPath("$.hostAccountId").value(host.toString()))
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.address").doesNotExist());
    assertThat(STUB.calls("POST", "/admin/v1/listings/").get(0).body())
        .isEqualTo("{\"reason\":\"ok\"}");

    // No body at all is allowed too.
    perform(
            MockMvcRequestBuilders.post("/admin/v1/moderation/listings/" + listing + "/approve"),
            admin())
        .andExpect(status().isOk());
  }

  @Test
  void listing_problems_pass_through() throws Exception {
    UUID own = UUID.randomUUID();
    UUID suspended = UUID.randomUUID();
    UUID published = UUID.randomUUID();
    STUB.on(
        "POST",
        "/admin/v1/listings/" + own + "/approve",
        403,
        Fixtures.problem(403, "forbidden", "Admins cannot moderate their own listings"));
    STUB.on(
        "POST",
        "/admin/v1/listings/" + suspended + "/approve",
        422,
        Fixtures.problem(422, "host-suspended", "The host is suspended"));
    STUB.on(
        "POST",
        "/admin/v1/listings/" + published + "/reject",
        409,
        Fixtures.problem(409, "illegal-listing-transition", "Not in review"));

    postJson("/admin/v1/moderation/listings/" + own + "/approve", "{}", admin())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Admins cannot moderate their own listings"));
    postJson("/admin/v1/moderation/listings/" + suspended + "/approve", "{}", admin())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/host-suspended"));
    postJson(
            "/admin/v1/moderation/listings/" + published + "/reject",
            "{\"reason\":\"nope\"}",
            admin())
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/illegal-listing-transition"));
    postJson(
            "/admin/v1/moderation/listings/" + UUID.randomUUID() + "/reject",
            "{\"reason\":\"x\"}",
            admin())
        .andExpect(status().isNotFound());
  }

  @Test
  void reject_requires_a_reason_and_bad_ids_are_400() throws Exception {
    UUID listing = UUID.randomUUID();
    postJson("/admin/v1/moderation/listings/" + listing + "/reject", "{}", admin())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("reason"));
    postJson(
            "/admin/v1/moderation/listings/" + listing + "/reject",
            "{\"reason\":\"" + "x".repeat(501) + "\"}",
            admin())
        .andExpect(status().isBadRequest());
    postJson("/admin/v1/moderation/listings/not-a-uuid/reject", "{\"reason\":\"x\"}", admin())
        .andExpect(status().isBadRequest());
    assertThat(STUB.received).isEmpty();
  }

  @Test
  void hide_review_forwards_and_drops_the_comment() throws Exception {
    UUID review = UUID.randomUUID();
    STUB.on("POST", "/admin/v1/reviews/" + review + "/hide", 200, Fixtures.review(review));

    var result =
        postJson(
                "/admin/v1/moderation/reviews/" + review + "/hide",
                "{\"reason\":\"abuse\"}",
                admin())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewId").value(review.toString()))
            .andExpect(jsonPath("$.hidden").value(true))
            .andExpect(jsonPath("$.rating").value(1))
            .andExpect(jsonPath("$.comment").doesNotExist());
    assertThat(result.andReturn().getResponse().getContentAsString()).doesNotContain("+91");
    assertThat(STUB.calls("POST", "/admin/v1/reviews/").get(0).body())
        .isEqualTo("{\"reason\":\"abuse\"}");
  }

  @Test
  void slow_or_redirecting_review_service_is_503() throws Exception {
    UUID slow = UUID.randomUUID();
    UUID redirecting = UUID.randomUUID();
    STUB.slow("POST", "/admin/v1/reviews/" + slow + "/hide", 2_000, Fixtures.review(slow));
    STUB.redirect("POST", "/admin/v1/reviews/" + redirecting + "/hide", "http://evil.test/steal");

    postJson("/admin/v1/moderation/reviews/" + slow + "/hide", "{\"reason\":\"abuse\"}", admin())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/review-unavailable"));
    postJson(
            "/admin/v1/moderation/reviews/" + redirecting + "/hide",
            "{\"reason\":\"abuse\"}",
            admin())
        .andExpect(status().isServiceUnavailable());
    // POSTs are never retried.
    assertThat(STUB.calls("POST", "/admin/v1/reviews/" + slow)).hasSize(1);
  }
}
