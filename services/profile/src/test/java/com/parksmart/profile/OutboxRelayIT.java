package com.parksmart.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.profile.ProfileUpdated;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "parksmart.outbox.relay.enabled=true",
      "parksmart.outbox.relay.poll-interval=100ms"
    })
@DirtiesContext
class OutboxRelayIT extends IntegrationTest {

  @Test
  void profile_updated_reaches_kafka_keyed_by_account() throws Exception {
    User user = driver();
    putJson("/api/v1/profiles/me", Map.of("displayName", "Relay Test"), user)
        .andExpect(status().isOk());

    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.PROFILE));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
              if (user.id().toString().equals(r.key())) {
                received.add(r);
              }
            }
            return !received.isEmpty();
          });
    }
    ProfileUpdated payload =
        (ProfileUpdated) EventJson.deserialize(received.get(0).value()).payload();
    assertThat(payload.displayName()).isEqualTo("Relay Test");
    assertThat(new String(received.get(0).headers().lastHeader("eventType").value()))
        .isEqualTo("ProfileUpdated");
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() ->
            jdbc.sql(
                        "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"
                            + " AND payload->'payload'->>'accountId' = :id")
                    .param("id", user.id().toString())
                    .query(Long.class)
                    .single()
                == 0);
  }

  private static KafkaConsumer<String, String> consumer() {
    Properties props = new Properties();
    props.putAll(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "relay-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    return new KafkaConsumer<>(props);
  }
}
