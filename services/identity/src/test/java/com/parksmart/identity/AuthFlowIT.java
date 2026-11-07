package com.parksmart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthFlowIT extends IntegrationTest {

  @Test
  void register_creates_pending_driver_and_publishes_events() throws Exception {
    String email = uniqueEmail();
    postJson("/api/v1/auth/register", new Register(email, PASSWORD, "2026-01"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
        .andExpect(jsonPath("$.roles[0]").value("DRIVER"))
        .andExpect(jsonPath("$.emailVerified").value(false))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());

    UUID id =
        jdbc.sql("SELECT id FROM accounts WHERE email = :e")
            .param("e", email)
            .query(UUID.class)
            .single();
    assertThat(countEvents(id, "AccountRegistered")).isEqualTo(1);
    assertThat(countEvents(id, "RoleGranted")).isEqualTo(1);
    assertThat(countEvents(id, "ContactDetailsChanged")).isEqualTo(1);
    assertThat(countEvents(id, "VerificationCodeIssued")).isEqualTo(1);

    String registered =
        jdbc.sql(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'AccountRegistered'"
                    + " AND payload->'payload'->>'accountId' = :id")
            .param("id", id.toString())
            .query(String.class)
            .single();
    assertThat(registered).doesNotContain(email);
  }

  @Test
  void stored_password_and_codes_are_hashed() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);
    String hash =
        jdbc.sql("SELECT password_hash FROM accounts WHERE id = :id")
            .param("id", id)
            .query(String.class)
            .single();
    assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);

    String code = latestCode(id, "EMAIL_VERIFY");
    Long plain =
        jdbc.sql("SELECT count(*) FROM verification_tokens WHERE token_hash = :c")
            .param("c", code)
            .query(Long.class)
            .single();
    assertThat(plain).isZero();
  }

  @Test
  void duplicate_email_is_rejected_case_insensitively() throws Exception {
    String email = uniqueEmail();
    register(email);
    postJson("/api/v1/auth/register", new Register(email.toUpperCase(), PASSWORD, "2026-01"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/email-taken"));
  }

  @Test
  void invalid_registration_returns_validation_problem() throws Exception {
    postJson("/api/v1/auth/register", new Register("not-an-email", "short", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/validation-failed"))
        .andExpect(jsonPath("$.errors.length()").value(3));

    postJson("/api/v1/auth/register", new Register(uniqueEmail(), "longbutnodigits", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/weak-password"));
  }

  @Test
  void login_returns_tokens_and_me_returns_own_account() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);
    JsonNode tokens = login(email, PASSWORD);
    assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(tokens.get("expiresIn").asLong()).isEqualTo(900);

    mvc.perform(authed(get("/api/v1/accounts/me"), tokens.get("accessToken").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.email").value(email));
  }

  @Test
  void wrong_password_and_unknown_email_look_the_same() throws Exception {
    String email = uniqueEmail();
    register(email);
    postJson("/api/v1/auth/login", new Login(email, "wrong password 1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-credentials"));
    postJson("/api/v1/auth/login", new Login(uniqueEmail(), "wrong password 1"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-credentials"));
  }

  @Test
  void five_wrong_passwords_lock_the_account() throws Exception {
    String email = uniqueEmail();
    register(email);
    for (int i = 0; i < 5; i++) {
      postJson("/api/v1/auth/login", new Login(email, "wrong password 1"))
          .andExpect(status().isUnauthorized());
    }
    // Locked: even the right password gets the same answer as a wrong one (no lock oracle).
    postJson("/api/v1/auth/login", new Login(email, PASSWORD))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-credentials"));
    assertThat(lockedUntil(email)).isNotNull();
  }

  @Test
  void parallel_wrong_passwords_all_count_towards_the_lock() throws Exception {
    String email = uniqueEmail();
    register(email);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(5);
    try {
      java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
      for (int i = 0; i < 5; i++) {
        results.add(
            pool.submit(() ->
                postJson("/api/v1/auth/login", new Login(email, "wrong password 1"))
                    .andReturn()
                    .getResponse()
                    .getStatus()));
      }
      for (var result : results) {
        assertThat(result.get()).isEqualTo(401);
      }
    } finally {
      pool.shutdown();
    }
    assertThat(lockedUntil(email)).isNotNull();
  }

  @Test
  void a_login_older_than_ninety_days_cannot_be_refreshed() throws Exception {
    String email = uniqueEmail();
    register(email);
    String refresh = login(email, PASSWORD).get("refreshToken").asText();
    jdbc.sql(
            "UPDATE refresh_tokens SET family_started_at = now() - interval '91 days'"
                + " WHERE account_id = (SELECT id FROM accounts WHERE email = :e)")
        .param("e", email)
        .update();
    postJson("/api/v1/auth/refresh", new Refresh(refresh)).andExpect(status().isUnauthorized());
  }

  private java.time.OffsetDateTime lockedUntil(String email) {
    return jdbc.sql("SELECT locked_until FROM accounts WHERE email = :e")
        .param("e", email)
        .query(java.time.OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  @Test
  void refresh_rotates_and_reuse_revokes_the_whole_family() throws Exception {
    String email = uniqueEmail();
    register(email);
    String first = login(email, PASSWORD).get("refreshToken").asText();

    JsonNode rotated =
        json.readTree(
            postJson("/api/v1/auth/refresh", new Refresh(first))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    String second = rotated.get("refreshToken").asText();
    assertThat(second).isNotEqualTo(first);

    // Replaying the old token is treated as theft...
    postJson("/api/v1/auth/refresh", new Refresh(first))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type").value("https://parksmart.dev/problems/invalid-refresh-token"));
    // ...which also kills the token that was legitimately issued after it.
    postJson("/api/v1/auth/refresh", new Refresh(second)).andExpect(status().isUnauthorized());
  }

  @Test
  void logout_revokes_own_refresh_token_only() throws Exception {
    String alice = uniqueEmail();
    String bob = uniqueEmail();
    register(alice);
    register(bob);
    JsonNode aliceTokens = login(alice, PASSWORD);
    JsonNode bobTokens = login(bob, PASSWORD);

    // Bob cannot log Alice out by presenting her refresh token.
    postJson(
            "/api/v1/auth/logout",
            new Refresh(aliceTokens.get("refreshToken").asText()),
            bobTokens.get("accessToken").asText())
        .andExpect(status().isNoContent());
    postJson("/api/v1/auth/refresh", new Refresh(aliceTokens.get("refreshToken").asText()))
        .andExpect(status().isOk());

    String bobRefresh = bobTokens.get("refreshToken").asText();
    postJson("/api/v1/auth/logout", new Refresh(bobRefresh), bobTokens.get("accessToken").asText())
        .andExpect(status().isNoContent());
    postJson("/api/v1/auth/refresh", new Refresh(bobRefresh)).andExpect(status().isUnauthorized());
  }

  @Test
  void protected_endpoints_require_a_token() throws Exception {
    mvc.perform(get("/api/v1/accounts/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/unauthorized"));
    mvc.perform(authed(get("/api/v1/accounts/me"), "not.a.jwt"))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/email/verification-requests"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void correlation_id_is_echoed() throws Exception {
    mvc.perform(get("/actuator/health").header("X-Correlation-Id", "test-corr-1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-Id", "test-corr-1"));
  }

  @Test
  void unknown_paths_are_denied() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    mvc.perform(get("/internal/v1/anything")).andExpect(status().isUnauthorized());
  }
}
