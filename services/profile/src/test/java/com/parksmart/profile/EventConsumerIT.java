package com.parksmart.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.identity.AccountRegistered;
import com.parksmart.common.events.review.RatingAggregateUpdated;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Real Kafka: events from Identity and Review reach the listener, duplicates are ignored. */
class EventConsumerIT extends IntegrationTest {

  private static final KafkaProducer<String, String> PRODUCER = producer();

  @AfterAll
  static void closeProducer() {
    PRODUCER.close();
  }

  private static void send(String topic, EventEnvelope<?> envelope) throws Exception {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, envelope.partitionKey(), EventJson.serialize(envelope));
    record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
    record.headers().add("eventId", envelope.eventId().toString().getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(record).get();
  }

  private static <T extends EventPayload> EventEnvelope<T> wrap(T payload) {
    return EventEnvelope.wrap(payload, "corr-" + UUID.randomUUID(), "test", Clock.systemUTC());
  }

  @Autowired KafkaListenerEndpointRegistry listeners;

  @Test
  void listener_uses_the_service_consumer_group() {
    assertThat(listeners.getListenerContainer("profile-events").getGroupId())
        .isEqualTo("profile-service");
  }

  private long profiles(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM profiles WHERE account_id = :id")
        .param("id", accountId)
        .query(Long.class)
        .single();
  }

  @Test
  void account_registered_creates_a_profile_once() throws Exception {
    UUID account = UUID.randomUUID();
    EventEnvelope<AccountRegistered> event = wrap(new AccountRegistered(account, Instant.now()));
    send(Topics.IDENTITY_ACCOUNT, event);
    send(Topics.IDENTITY_ACCOUNT, event); // redelivery

    await().atMost(Duration.ofSeconds(30)).until(() -> profiles(account) == 1);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() ->
            jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                    .param("id", event.eventId())
                    .query(Long.class)
                    .single()
                == 1);
  }

  @Test
  void driver_ratings_are_cached_and_older_ones_ignored() throws Exception {
    UUID driver = UUID.randomUUID();
    Instant now = Instant.now();
    send(
        Topics.REVIEW,
        wrap(new RatingAggregateUpdated("DRIVER", driver, new BigDecimal("4.67"), 3, now)));
    send(
        Topics.REVIEW,
        wrap(
            new RatingAggregateUpdated(
                "DRIVER", driver, new BigDecimal("2.00"), 1, now.minusSeconds(60))));
    UUID listing = UUID.randomUUID();
    send(
        Topics.REVIEW,
        wrap(new RatingAggregateUpdated("LISTING", listing, new BigDecimal("5.0"), 1, now)));

    User user = new User(driver, token(driver, List.of("DRIVER")));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            get("/api/v1/profiles/me", user)
                .andExpect(jsonPath("$.ratingAvg").value("4.67"))
                .andExpect(jsonPath("$.ratingCount").value(3)));
    // Give the older event time to be consumed, then check it did not win.
    Thread.sleep(1000);
    get("/api/v1/profiles/me", user).andExpect(jsonPath("$.ratingAvg").value("4.67"));
    assertThat(profiles(listing)).isZero();
  }

  @Test
  void unparseable_records_go_to_the_dead_letter_topic() throws Exception {
    String key = "poison-" + UUID.randomUUID();
    ProducerRecord<String, String> poison =
        new ProducerRecord<>(Topics.IDENTITY_ACCOUNT, key, "{not json");
    poison.headers().add("eventType", "AccountRegistered".getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(poison).get();

    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.deadLetterTopic(Topics.IDENTITY_ACCOUNT)));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> {
            for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(200))) {
              if (key.equals(r.key())) {
                return true;
              }
            }
            return false;
          });
    }
  }

  private static KafkaProducer<String, String> producer() {
    Properties props = new Properties();
    props.putAll(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    return new KafkaProducer<>(props);
  }

  private static KafkaConsumer<String, String> consumer() {
    Properties props = new Properties();
    props.putAll(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    return new KafkaConsumer<>(props);
  }
}
