package com.parksmart.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.listing.ListingCreated;
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
class OutboxRelayIT extends ListingFixtures {

  @Test
  void listing_created_reaches_kafka_keyed_by_listing() throws Exception {
    User host = host();
    UUID listingId = create(host);

    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.LISTING));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
              if (listingId.toString().equals(r.key())) {
                received.add(r);
              }
            }
            return !received.isEmpty();
          });
    }
    ListingCreated payload =
        (ListingCreated) EventJson.deserialize(received.get(0).value()).payload();
    assertThat(payload.hostAccountId()).isEqualTo(host.id());
    assertThat(new String(received.get(0).headers().lastHeader("eventType").value()))
        .isEqualTo("ListingCreated");
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() ->
            jdbc.sql(
                        "SELECT count(*) FROM outbox_events WHERE published_at IS NULL"
                            + " AND payload->'payload'->>'listingId' = :id")
                    .param("id", listingId.toString())
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
