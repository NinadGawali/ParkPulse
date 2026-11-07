package com.parksmart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Real resend limits (the shared test profile disables the cooldown). */
@TestPropertySource(
    properties = {
      "parksmart.identity.codes.resend-cooldown=60s",
      "parksmart.identity.codes.max-per-hour=5"
    })
class CodeThrottleIT extends IntegrationTest {

  record Forgot(String email) {}

  @Test
  void a_new_code_cannot_be_requested_within_the_cooldown() throws Exception {
    String email = uniqueEmail();
    register(email); // issues the first EMAIL_VERIFY code
    String access = login(email, PASSWORD).get("accessToken").asText();

    mvc.perform(authed(post("/api/v1/auth/email/verification-requests"), access))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/code-recently-sent"));
  }

  @Test
  void forgot_password_is_throttled_silently() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);

    postJson("/api/v1/auth/password/forgot", new Forgot(email)).andExpect(status().isAccepted());
    postJson("/api/v1/auth/password/forgot", new Forgot(email)).andExpect(status().isAccepted());

    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM verification_tokens WHERE account_id = :id"
                        + " AND purpose = 'PASSWORD_RESET'")
                .param("id", id)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }
}
