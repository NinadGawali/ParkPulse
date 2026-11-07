package com.parksmart.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayoutAndPreferencesIT extends IntegrationTest {

  private static final String PAYOUTS = "/api/v1/profiles/me/payout-accounts";
  private static final String PREFS = "/api/v1/notification-preferences";

  private static Map<String, Object> payout(String token) {
    return Map.of("processorType", "FAKE", "processorToken", token);
  }

  private static String token() {
    return "acct_" + UUID.randomUUID().toString().replace("-", "");
  }

  @Test
  void drivers_cannot_use_payout_accounts() throws Exception {
    User driver = driver();
    get(PAYOUTS, driver).andExpect(status().isForbidden());
    postJson(PAYOUTS, payout(token()), driver).andExpect(status().isForbidden());
    delete(PAYOUTS + "/" + UUID.randomUUID(), driver).andExpect(status().isForbidden());
  }

  @Test
  void host_adds_lists_and_deletes_without_ever_seeing_the_token() throws Exception {
    User host = host();
    String token = token();
    String response =
        postJson(PAYOUTS, payout(token), host)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.verified").value(false))
            .andExpect(jsonPath("$.tokenHint").value("…" + token.substring(token.length() - 4)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(response).doesNotContain(token);
    String id = json.readTree(response).get("id").asText();

    get(PAYOUTS, host).andExpect(jsonPath("$.items.length()").value(1));
    delete(PAYOUTS + "/" + id, host).andExpect(status().isNoContent());
    get(PAYOUTS, host).andExpect(jsonPath("$.items.length()").value(0));
    delete(PAYOUTS + "/" + id, host).andExpect(status().isNotFound());
  }

  @Test
  void another_hosts_payout_account_is_403() throws Exception {
    User owner = host();
    String id = body(postJson(PAYOUTS, payout(token()), owner)).get("id").asText();
    delete(PAYOUTS + "/" + id, host()).andExpect(status().isForbidden());
    get(PAYOUTS, owner).andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void duplicate_tokens_and_limit() throws Exception {
    User host = host();
    String token = token();
    postJson(PAYOUTS, payout(token), host).andExpect(status().isCreated());
    postJson(PAYOUTS, payout(token), host)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/payout-registered"));
    // Another host cannot be blocked (or learn anything) by a token someone else added;
    // Payment verifies ownership with the processor before paying out.
    postJson(PAYOUTS, payout(token), host()).andExpect(status().isCreated());
    postJson(PAYOUTS, payout(token()), host).andExpect(status().isCreated());
    postJson(PAYOUTS, payout(token()), host).andExpect(status().isCreated());
    postJson(PAYOUTS, payout(token()), host)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/payout-limit"));
    postJson(PAYOUTS, Map.of("processorType", "FAKE", "processorToken", "x y"), host)
        .andExpect(status().isBadRequest());
  }

  @Test
  void preferences_default_on_and_update_only_listed_channels() throws Exception {
    User user = driver();
    get(PREFS, user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preferences.length()").value(4))
        .andExpect(jsonPath("$.preferences[?(@.enabled == false)]").isEmpty());

    putJson(PREFS, Map.of("preferences", List.of(Map.of("channel", "SMS", "enabled", false))), user)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.preferences[?(@.channel == 'SMS')].enabled").value(false))
        .andExpect(jsonPath("$.preferences[?(@.channel == 'EMAIL')].enabled").value(true));
    get(PREFS, user)
        .andExpect(jsonPath("$.preferences[?(@.channel == 'SMS')].enabled").value(false));
    var changed = events("NotificationPreferencesChanged", "accountId", user.id());
    assertThat(changed).hasSize(1);
    assertThat(changed.get(0).get("payload").get("sms").asBoolean()).isFalse();
    assertThat(changed.get(0).get("payload").get("email").asBoolean()).isTrue();

    // Another user is unaffected.
    get(PREFS, driver())
        .andExpect(jsonPath("$.preferences[?(@.channel == 'SMS')].enabled").value(true));
  }

  @Test
  void in_app_cannot_be_disabled_and_duplicates_are_rejected() throws Exception {
    User user = driver();
    putJson(
            PREFS,
            Map.of("preferences", List.of(Map.of("channel", "IN_APP", "enabled", false))),
            user)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/channel-required"));
    putJson(
            PREFS,
            Map.of(
                "preferences",
                List.of(
                    Map.of("channel", "SMS", "enabled", false),
                    Map.of("channel", "SMS", "enabled", true))),
            user)
        .andExpect(status().isBadRequest());
    putJson(PREFS, Map.of("preferences", List.of()), user).andExpect(status().isBadRequest());
  }
}
