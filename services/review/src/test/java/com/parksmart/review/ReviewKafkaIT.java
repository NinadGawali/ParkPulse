package com.parksmart.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingCompleted;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Real Kafka: BookingCompleted opens eligibility, once. */
class ReviewKafkaIT extends IntegrationTest {

  @Autowired KafkaListenerEndpointRegistry registry;

  @Test
  void booking_completed_makes_the_booking_reviewable() throws Exception {
    assertThat(registry.getListenerContainer("review-events").getGroupId())
        .isEqualTo("review-service");
    UUID booking = UUID.randomUUID();
    User driver = driver();
    User host = host();
    Instant now = Instant.now();
    EventEnvelope<EventPayload> e =
        EventEnvelope.wrap(
            new BookingCompleted(
                booking,
                UUID.randomUUID(),
                driver.id(),
                host.id(),
                now.minus(3, ChronoUnit.HOURS),
                now.minus(1, ChronoUnit.HOURS),
                now,
                now.plus(14, ChronoUnit.DAYS)),
            "corr-1",
            "booking-service",
            Clock.systemUTC());
    Properties props = new Properties();
    props.putAll(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      for (int i = 0; i < 2; i++) { // a redelivery must be harmless
        ProducerRecord<String, String> r =
            new ProducerRecord<>(Topics.BOOKING, booking.toString(), EventJson.serialize(e));
        r.headers().add("eventType", "BookingCompleted".getBytes(StandardCharsets.UTF_8));
        producer.send(r).get();
      }
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            assertThat(
                    jdbc.sql("SELECT count(*) FROM review_eligibility WHERE booking_id = :b")
                        .param("b", booking)
                        .query(Long.class)
                        .single())
                .isEqualTo(1));
    postJson("/api/v1/reviews", Map.of("bookingId", booking, "rating", 5), driver)
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
  }
}
