package com.parksmart.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.listing.AvailabilityRuleData;
import com.parksmart.common.events.listing.ListingPaused;
import com.parksmart.common.events.listing.ListingPublished;
import com.parksmart.common.events.listing.ListingSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Real Listing and Booking events build the index. */
class SearchKafkaIT extends IntegrationTest {

  private static final KafkaProducer<String, String> PRODUCER = producer();

  @Autowired KafkaListenerEndpointRegistry registry;

  @AfterAll
  static void close() {
    PRODUCER.close();
  }

  private static void send(String topic, EventPayload payload) throws Exception {
    EventEnvelope<EventPayload> e =
        EventEnvelope.wrap(payload, "corr-" + UUID.randomUUID(), "test", Clock.systemUTC());
    ProducerRecord<String, String> r =
        new ProducerRecord<>(topic, e.partitionKey(), EventJson.serialize(e));
    r.headers().add("eventType", e.eventType().getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(r).get();
  }

  @Test
  void consumer_group() {
    assertThat(registry.getListenerContainer("search-events").getGroupId())
        .isEqualTo("search-service");
  }

  @Test
  void published_listings_are_found_booked_windows_drop_out_and_paused_ones_vanish()
      throws Exception {
    double lat = -45 + Math.random() * 10;
    double lng = 100 + Math.random() * 10;
    UUID listing = UUID.randomUUID();
    UUID host = UUID.randomUUID();
    List<AvailabilityRuleData> rules =
        Arrays.stream(DayOfWeek.values())
            .map(d -> new AvailabilityRuleData(d.name(), "00:00", "24:00"))
            .toList();
    send(
        Topics.LISTING,
        new ListingPublished(
            new ListingSnapshot(
                listing,
                host,
                "PUBLISHED",
                "Kafka spot",
                "1 Event Road",
                lat,
                lng,
                "Asia/Kolkata",
                new BigDecimal("55.00"),
                "INR",
                "SUV",
                List.of("COVERED"),
                true,
                "MODERATE",
                0,
                null,
                rules,
                List.of()),
            Instant.now()));
    String search = "/api/v1/search/listings?lat=" + lat + "&lng=" + lng;
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            get(search, null)
                .andExpect(jsonPath("$.items[0].listingId").value(listing.toString())));

    Instant start = Instant.parse("2030-03-04T05:00:00Z");
    send(
        Topics.BOOKING,
        new BookingConfirmed(
            UUID.randomUUID(),
            listing,
            UUID.randomUUID(),
            host,
            start,
            start.plusSeconds(3600),
            start.plusSeconds(3600),
            new BigDecimal("55.00"),
            "INR",
            Instant.now()));
    String window = search + "&startTime=" + start + "&endTime=" + start.plusSeconds(1800);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> get(window, null).andExpect(jsonPath("$.items.length()").value(0)));

    send(Topics.LISTING, new ListingPaused(listing, host, Instant.now().plusSeconds(1)));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> get("/api/v1/search/listings/" + listing, null).andExpect(status().isNotFound()));
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
