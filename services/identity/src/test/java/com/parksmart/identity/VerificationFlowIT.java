package com.parksmart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

class VerificationFlowIT extends IntegrationTest {

  @Test
  void email_verification_activates_the_account() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);
    String token = login(email, PASSWORD).get("accessToken").asText();
    String code = latestCode(id, "EMAIL_VERIFY");

    postJson("/api/v1/auth/email/verify", new Code(wrong(code)), token)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/invalid-code"));
    postJson("/api/v1/auth/email/verify", new Code(code), token).andExpect(status().isNoContent());

    mvc.perform(authed(get("/api/v1/accounts/me"), token))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.emailVerified").value(true));
    assertThat(countEvents(id, "EmailVerified")).isEqualTo(1);

    // A used code cannot be replayed, and re-requesting is a conflict.
    postJson("/api/v1/auth/email/verify", new Code(code), token)
        .andExpect(status().isUnprocessableEntity());
    mvc.perform(authed(post("/api/v1/auth/email/verification-requests"), token))
        .andExpect(status().isConflict());
  }

  @Test
  void wrong_guesses_are_counted_and_the_code_is_burned_after_five() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);
    String token = login(email, PASSWORD).get("accessToken").asText();
    String code = latestCode(id, "EMAIL_VERIFY");

    for (int i = 0; i < 5; i++) {
      postJson("/api/v1/auth/email/verify", new Code(wrong(code)), token)
          .andExpect(status().isUnprocessableEntity());
    }
    postJson("/api/v1/auth/email/verify", new Code(code), token)
        .andExpect(status().isUnprocessableEntity());

    // A fresh code works.
    mvc.perform(authed(post("/api/v1/auth/email/verification-requests"), token))
        .andExpect(status().isAccepted());
    postJson("/api/v1/auth/email/verify", new Code(latestCode(id, "EMAIL_VERIFY")), token)
        .andExpect(status().isNoContent());
  }

  @Test
  void becoming_host_requires_verified_email_and_shows_up_after_refresh() throws Exception {
    String email = uniqueEmail();
    UUID id = register(email);
    JsonNode tokens = login(email, PASSWORD);
    String access = tokens.get("accessToken").asText();

    mvc.perform(authed(post("/api/v1/accounts/me/roles/host"), access))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/account-not-active"));

    postJson("/api/v1/auth/email/verify", new Code(latestCode(id, "EMAIL_VERIFY")), access)
        .andExpect(status().isNoContent());
    mvc.perform(authed(post("/api/v1/accounts/me/roles/host"), access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles.length()").value(2));
    // Idempotent: a second call does not emit a second event.
    mvc.perform(authed(post("/api/v1/accounts/me/roles/host"), access)).andExpect(status().isOk());
    assertThat(countEvents(id, "RoleGranted")).isEqualTo(2); // DRIVER + HOST

    String refreshed =
        json.readTree(
                postJson("/api/v1/auth/refresh", new Refresh(tokens.get("refreshToken").asText()))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("accessToken")
            .asText();
    assertThat(claims(refreshed).get("roles").toString()).contains("HOST", "DRIVER");
  }

  @Test
  void phone_verification_and_change() throws Exception {
    String email = uniqueEmail();
    String access = activeUser(email).get("accessToken").asText();
    UUID id = accountId(email);
    String phone = randomPhone();

    postJson("/api/v1/auth/phone/verification-requests", new Phone(phone), access)
        .andExpect(status().isAccepted());
    postJson("/api/v1/auth/phone/verify", new Code(latestCode(id, "PHONE_VERIFY")), access)
        .andExpect(status().isNoContent());
    mvc.perform(authed(get("/api/v1/accounts/me"), access))
        .andExpect(jsonPath("$.phone").value(phone))
        .andExpect(jsonPath("$.phoneVerified").value(true));
    assertThat(countEvents(id, "PhoneVerified")).isEqualTo(1);

    // Same number again: conflict.
    postJson("/api/v1/auth/phone/verification-requests", new Phone(phone), access)
        .andExpect(status().isConflict());

    // Switching numbers un-verifies the old one immediately.
    postJson("/api/v1/auth/phone/verification-requests", new Phone(randomPhone()), access)
        .andExpect(status().isAccepted());
    mvc.perform(authed(get("/api/v1/accounts/me"), access))
        .andExpect(jsonPath("$.phoneVerified").value(false));
    assertThat(countEvents(id, "PhoneUnverified")).isEqualTo(1);
  }

  @Test
  void a_phone_verified_on_another_account_is_rejected() throws Exception {
    String phone = randomPhone();
    String first = uniqueEmail();
    String firstAccess = activeUser(first).get("accessToken").asText();
    postJson("/api/v1/auth/phone/verification-requests", new Phone(phone), firstAccess)
        .andExpect(status().isAccepted());
    postJson(
            "/api/v1/auth/phone/verify",
            new Code(latestCode(accountId(first), "PHONE_VERIFY")),
            firstAccess)
        .andExpect(status().isNoContent());

    String secondAccess = activeUser(uniqueEmail()).get("accessToken").asText();
    postJson("/api/v1/auth/phone/verification-requests", new Phone(phone), secondAccess)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/phone-in-use"));
  }

  @Test
  void invalid_phone_format_is_rejected() throws Exception {
    String access = activeUser(uniqueEmail()).get("accessToken").asText();
    postJson("/api/v1/auth/phone/verification-requests", new Phone("98765"), access)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("phone"));
  }

  private UUID accountId(String email) {
    return jdbc.sql("SELECT id FROM accounts WHERE email = :e")
        .param("e", email)
        .query(UUID.class)
        .single();
  }

  private JsonNode claims(String jwt) throws Exception {
    return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
  }

  private static String wrong(String code) {
    return code.equals("000000") ? "111111" : "000000";
  }

  private static String randomPhone() {
    return "+9198" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
  }
}
