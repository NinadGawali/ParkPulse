package com.parksmart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordResetIT extends IntegrationTest {

  record Forgot(String email) {}

  record Reset(String token, String newPassword) {}

  @Test
  void forgot_password_never_reveals_whether_the_email_exists() throws Exception {
    postJson("/api/v1/auth/password/forgot", new Forgot(uniqueEmail()))
        .andExpect(status().isAccepted());
  }

  @Test
  void reset_changes_password_and_revokes_sessions() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);
    String oldRefresh = login(email, PASSWORD).get("refreshToken").asText();

    postJson("/api/v1/auth/password/forgot", new Forgot(email)).andExpect(status().isAccepted());
    String token = latestCode(id, "PASSWORD_RESET");
    assertThat(token).hasSizeGreaterThan(30);

    String newPassword = "brand new secret 7";
    postJson("/api/v1/auth/password/reset", new Reset(token, newPassword))
        .andExpect(status().isNoContent());

    postJson("/api/v1/auth/login", new Login(email, PASSWORD)).andExpect(status().isUnauthorized());
    login(email, newPassword);
    postJson("/api/v1/auth/refresh", new Refresh(oldRefresh)).andExpect(status().isUnauthorized());

    // Single use.
    postJson("/api/v1/auth/password/reset", new Reset(token, "another secret 99"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-token"));
  }

  @Test
  void reset_with_unknown_token_is_rejected() throws Exception {
    postJson("/api/v1/auth/password/reset", new Reset("x".repeat(43), "brand new secret 7"))
        .andExpect(status().isUnprocessableEntity());
  }
}
