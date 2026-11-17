package com.parksmart.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.identity.AccountRegistered;
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
// Own context with the relay on; closed afterwards so it cannot delete rows other tests read.
@DirtiesContext
class OutboxRelayIT extends IntegrationTest {

  @Test
  void events_reach_kafka_keyed_by_account_and_private_rows_are_deleted() throws Exception {
    UUID id = register(uniqueEmail());

    List<ConsumerRecord<String, String>> account = new ArrayList<>();
    List<ConsumerRecord<String, String>> privateRecords = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.IDENTITY_ACCOUNT, Topics.IDENTITY_PRIVATE));
      await()
          .atMost(Duration.ofSeconds(30))
          .untilAsserted(() -> {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
              if (!id.toString().equals(record.key())) {
                continue;
              }
              (record.topic().equals(Topics.IDENTITY_PRIVATE) ? privateRecords : account)
                  .add(record);
            }
            assertThat(account).hasSizeGreaterThanOrEqualTo(2);
            assertThat(privateRecords).hasSizeGreaterThanOrEqualTo(2);
          });
    }

    EventEnvelope<EventPayload> registered =
        account.stream()
            .map(r -> EventJson.deserialize(r.value()))
            .filter(e -> e.payload() instanceof AccountRegistered)
            .findFirst()
            .orElseThrow();
    assertThat(registered.producer()).isEqualTo("identity-service");
    assertThat(registered.correlationId()).isNotBlank();
    assertThat(new String(account.get(0).headers().lastHeader("eventType").value()))
        .isIn("AccountRegistered", "RoleGranted");

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          Long privateRows =
              jdbc.sql(
                      "SELECT count(*) FROM outbox_events WHERE topic = :t"
                          + " AND payload->'payload'->>'accountId' = :id")
                  .param("t", Topics.IDENTITY_PRIVATE)
                  .param("id", id.toString())
                  .query(Long.class)
                  .single();
          assertThat(privateRows).isZero();
        });
    Long published =
        jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NOT NULL"
                    + " AND payload->'payload'->>'accountId' = :id")
            .param("id", id.toString())
            .query(Long.class)
            .single();
    assertThat(published).isGreaterThanOrEqualTo(2);
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
