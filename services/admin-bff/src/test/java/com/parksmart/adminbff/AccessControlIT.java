package com.parksmart.adminbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Every endpoint is ADMIN only: 401 without a token, 403 for DRIVER/HOST, nothing forwarded. */
class AccessControlIT extends IntegrationTest {

  static Stream<Arguments> endpoints() {
    UUID id = UUID.randomUUID();
    return Stream.of(
        Arguments.of(
            "GET moderation queue",
            (Supplier<MockHttpServletRequestBuilder>)
                () -> MockMvcRequestBuilders.get("/admin/v1/moderation/listings")),
        Arguments.of(
            "POST approve",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    json(
                        MockMvcRequestBuilders.post(
                            "/admin/v1/moderation/listings/" + id + "/approve"),
                        "{}")),
        Arguments.of(
            "POST reject",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    json(
                        MockMvcRequestBuilders.post(
                            "/admin/v1/moderation/listings/" + id + "/reject"),
                        "{\"reason\":\"blurry photos\"}")),
        Arguments.of(
            "POST hide review",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    json(
                        MockMvcRequestBuilders.post("/admin/v1/moderation/reviews/" + id + "/hide"),
                        "{\"reason\":\"abuse\"}")),
        Arguments.of(
            "GET booking case",
            (Supplier<MockHttpServletRequestBuilder>)
                () -> MockMvcRequestBuilders.get("/admin/v1/bookings/" + id)),
        Arguments.of(
            "POST refund",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    json(
                            MockMvcRequestBuilders.post("/admin/v1/bookings/" + id + "/refunds"),
                            "{\"amount\":\"10.00\",\"reason\":\"dispute\"}")
                        .header("Idempotency-Key", "refund-key-1")));
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
    return b.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void anonymous_is_401(String name, Supplier<MockHttpServletRequestBuilder> request)
      throws Exception {
    perform(request.get(), null)
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/unauthorized"));
    assertThat(STUB.received).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("endpoints")
  void driver_and_host_are_403_and_nothing_is_forwarded(
      String name, Supplier<MockHttpServletRequestBuilder> request) throws Exception {
    for (User caller : List.of(user("DRIVER"), user("DRIVER", "HOST"), user("HOST"))) {
      perform(request.get(), caller)
          .andExpect(status().isForbidden())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/forbidden"));
    }
    assertThat(STUB.received).isEmpty();
  }

  @Test
  void forged_token_is_401() throws Exception {
    perform(
            MockMvcRequestBuilders.get("/admin/v1/moderation/listings"),
            new User(UUID.randomUUID(), "not-a-jwt"))
        .andExpect(status().isUnauthorized());
    assertThat(STUB.received).isEmpty();
  }

  @Test
  void unknown_paths_are_denied_and_health_is_public() throws Exception {
    perform(MockMvcRequestBuilders.get("/api/v1/anything"), admin())
        .andExpect(status().isForbidden());
    perform(MockMvcRequestBuilders.get("/actuator/health"), null).andExpect(status().isOk());
  }
}
