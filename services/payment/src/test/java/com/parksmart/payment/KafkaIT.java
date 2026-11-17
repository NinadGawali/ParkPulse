package com.parksmart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingRequested;
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
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/** Real Kafka in and out: BookingRequested creates the intent; the relay publishes results. */
@TestPropertySource(
    properties = {
      "parksmart.outbox.relay.enabled=true",
      "parksmart.outbox.relay.poll-interval=100ms"
    })
@DirtiesContext
class KafkaIT extends PaymentIT {

  private static final KafkaProducer<String, String> PRODUCER = producer();

  @AfterAll
  static void close() {
    PRODUCER.close();
  }

  @Test
  void booking_requested_creates_the_intent_and_payment_authorized_is_published() throws Exception {
    UUID booking = UUID.randomUUID();
    User driver = driver();
    EventEnvelope<EventPayload> envelope =
        EventEnvelope.wrap(
            new BookingRequested(
                booking,
                UUID.randomUUID(),
                driver.id(),
                UUID.randomUUID(),
                Instant.now(),
                Instant.now().plusSeconds(3600),
                new BigDecimal("75.00"),
                "INR",
                true,
                clock.instant().plus(Duration.ofMinutes(10)),
                Instant.now()),
            "corr-1",
            "booking-service",
            Clock.systemUTC());
    ProducerRecord<String, String> record =
        new ProducerRecord<>(Topics.BOOKING, booking.toString(), EventJson.serialize(envelope));
    record.headers().add("eventType", "BookingRequested".getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(record).get();

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() ->
            jdbc.sql("SELECT count(*) FROM payment_intents WHERE booking_id = :b")
                    .param("b", booking)
                    .query(Long.class)
                    .single()
                == 1);
    Deal deal = new Deal(booking, driver, null, new BigDecimal("75.00"));
    authorize(deal, "auth-" + UUID.randomUUID(), "fake_ok");

    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.PAYMENT));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> {
            for (var r : consumer.poll(Duration.ofMillis(200))) {
              if (booking.toString().equals(r.key())
                  && "PaymentAuthorized"
                      .equals(new String(r.headers().lastHeader("eventType").value()))) {
                return true;
              }
            }
            return false;
          });
    }
  }

  @Test
  void unparseable_booking_events_go_to_the_dead_letter_topic() throws Exception {
    String key = "poison-" + UUID.randomUUID();
    ProducerRecord<String, String> poison = new ProducerRecord<>(Topics.BOOKING, key, "{bad");
    poison.headers().add("eventType", "BookingConfirmed".getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(poison).get();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.deadLetterTopic(Topics.BOOKING)));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> {
            for (var r : consumer.poll(Duration.ofMillis(200))) {
              if (key.equals(r.key())) {
                return true;
              }
            }
            return false;
          });
    }
    assertThat(key).isNotBlank();
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
            ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    return new KafkaConsumer<>(props);
  }
}
