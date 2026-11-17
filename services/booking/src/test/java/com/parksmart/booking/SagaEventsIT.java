package com.parksmart.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.listing.ListingArchived;
import com.parksmart.common.events.payment.PaymentAuthorized;
import com.parksmart.common.events.payment.PaymentFailed;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** The saga over real Kafka: Payment and Listing events move bookings. */
class SagaEventsIT extends BookingIT {

  private static final KafkaProducer<String, String> PRODUCER = producer();

  @Autowired KafkaListenerEndpointRegistry registry;

  @AfterAll
  static void closeProducer() {
    PRODUCER.close();
  }

  private static EventEnvelope<EventPayload> send(String topic, EventPayload payload)
      throws Exception {
    EventEnvelope<EventPayload> envelope =
        EventEnvelope.wrap(payload, "corr-" + UUID.randomUUID(), "test", Clock.systemUTC());
    return send(topic, envelope);
  }

  private static EventEnvelope<EventPayload> send(
      String topic, EventEnvelope<EventPayload> envelope) throws Exception {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, envelope.partitionKey(), EventJson.serialize(envelope));
    record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(record).get();
    return envelope;
  }

  @Test
  void listener_uses_the_service_consumer_group() {
    assertThat(registry.getListenerContainer("booking-events").getGroupId())
        .isEqualTo("booking-service");
  }

  @Test
  void payment_authorized_confirms_once_and_payment_failed_expires() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), true, "FLEXIBLE", 0);
    User driver = driver();
    UUID ok = id(booked(driver, listing, slot(24), slot(25)));
    UUID declined = id(booked(driver, listing, slot(48), slot(49)));

    var authorized =
        send(
            Topics.PAYMENT,
            new PaymentAuthorized(
                UUID.randomUUID(),
                ok,
                driver.id(),
                host.id(),
                new BigDecimal("60.00"),
                "INR",
                Instant.now()));
    send(Topics.PAYMENT, authorized); // redelivery
    send(
        Topics.PAYMENT,
        new PaymentFailed(
            UUID.randomUUID(), declined, driver.id(), host.id(), "card_declined", Instant.now()));

    await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(ok).equals("CONFIRMED"));
    await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(declined).equals("EXPIRED"));
    Thread.sleep(500);
    assertThat(events("BookingConfirmed", "bookingId", ok)).hasSize(1);
  }

  @Test
  void listing_archived_cancels_upcoming_bookings() throws Exception {
    User host = host();
    UUID listing = listings.listing(host.id(), true, "FLEXIBLE", 0);
    UUID b = id(booked(driver(), listing, slot(24), slot(25)));
    authorize(b);
    send(Topics.LISTING, new ListingArchived(listing, host.id(), Instant.now()));
    await().atMost(Duration.ofSeconds(30)).until(() -> statusOf(b).equals("CANCELLED_BY_HOST"));
  }

  @Test
  void unparseable_records_go_to_the_dead_letter_topic() throws Exception {
    String key = "poison-" + UUID.randomUUID();
    ProducerRecord<String, String> poison = new ProducerRecord<>(Topics.PAYMENT, key, "{nope");
    poison.headers().add("eventType", "PaymentAuthorized".getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(poison).get();
    Properties props = new Properties();
    props.putAll(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "dlt-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(Topics.deadLetterTopic(Topics.PAYMENT)));
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
}
