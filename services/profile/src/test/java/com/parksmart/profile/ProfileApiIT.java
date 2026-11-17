package com.parksmart.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ProfileApiIT extends IntegrationTest {

  @Test
  void my_profile_is_created_on_first_read() throws Exception {
    User user = driver();
    get("/api/v1/profiles/me", user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(user.id().toString()))
        .andExpect(jsonPath("$.displayName").isEmpty())
        .andExpect(jsonPath("$.hasPhoto").value(false))
        .andExpect(jsonPath("$.ratingAvg").isEmpty())
        .andExpect(jsonPath("$.ratingCount").value(0));
    get("/api/v1/profiles/me", user).andExpect(status().isOk());
    Long rows =
        jdbc.sql("SELECT count(*) FROM profiles WHERE account_id = :id")
            .param("id", user.id())
            .query(Long.class)
            .single();
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void update_trims_fields_and_publishes_profile_updated_once_per_name_change() throws Exception {
    User user = driver();
    Map<String, Object> body = Map.of("displayName", "  Asha  ", "bio", "Hi", "city", "Pune");
    putJson("/api/v1/profiles/me", body, user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Asha"))
        .andExpect(jsonPath("$.city").value("Pune"));
    // Same name, different bio: no public change, no event.
    putJson("/api/v1/profiles/me", Map.of("displayName", "Asha", "bio", "Hello"), user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bio").value("Hello"))
        .andExpect(jsonPath("$.city").isEmpty());

    List<JsonNode> events = events("ProfileUpdated", "accountId", user.id());
    assertThat(events).hasSize(1);
    JsonNode payload = events.get(0).get("payload");
    assertThat(payload.get("displayName").asText()).isEqualTo("Asha");
    assertThat(payload.get("hasPhoto").asBoolean()).isFalse();
    assertThat(events.get(0).get("producer").asText()).isEqualTo("profile-service");
  }

  @Test
  void public_profile_hides_private_fields() throws Exception {
    User owner = driver();
    putJson(
            "/api/v1/profiles/me",
            Map.of("displayName", "Ravi", "bio", "secret bio", "city", "Mumbai"),
            owner)
        .andExpect(status().isOk());

    get("/api/v1/profiles/" + owner.id(), driver())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Ravi"))
        .andExpect(jsonPath("$.bio").doesNotExist())
        .andExpect(jsonPath("$.city").doesNotExist())
        .andExpect(jsonPath("$.ratingCount").value(0));
  }

  @Test
  void unknown_public_profile_is_404_problem() throws Exception {
    get("/api/v1/profiles/" + java.util.UUID.randomUUID(), driver())
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/not-found"));
  }

  @Test
  void anonymous_and_forged_tokens_are_401() throws Exception {
    get("/api/v1/profiles/me", null).andExpect(status().isUnauthorized());
    User forged = new User(java.util.UUID.randomUUID(), "not.a.jwt");
    get("/api/v1/profiles/me", forged)
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void invalid_update_is_400_with_field_errors() throws Exception {
    putJson("/api/v1/profiles/me", Map.of("displayName", "x"), driver())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("displayName"));
    putJson("/api/v1/profiles/me", Map.of("displayName", "bad\u0007name"), driver())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-displayName"));
  }

  @Test
  void health_is_public() throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/actuator/health"))
        .andExpect(status().isOk());
  }
}
