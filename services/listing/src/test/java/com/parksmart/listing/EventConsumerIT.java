package com.parksmart.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.identity.AccountSuspended;
import com.parksmart.common.events.identity.PhoneUnverified;
import com.parksmart.common.events.identity.PhoneVerified;
import com.parksmart.common.events.profile.ProfileUpdated;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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

/** Real Kafka: Identity, Profile and Booking events drive the two projections. */
class EventConsumerIT extends ListingFixtures {

  private static final KafkaProducer<String, String> PRODUCER = producer();

  @Autowired KafkaListenerEndpointRegistry listeners;

  @AfterAll
  static void closeProducer() {
    PRODUCER.close();
  }

  private static void send(String topic, EventPayload payload) throws Exception {
    EventEnvelope<EventPayload> envelope =
        EventEnvelope.wrap(payload, "corr-" + UUID.randomUUID(), "test", Clock.systemUTC());
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, envelope.partitionKey(), EventJson.serialize(envelope));
    record.headers().add("eventType", envelope.eventType().getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(record).get();
  }

  private Map<String, Object> eligibility(UUID host) {
    return jdbc.sql("SELECT * FROM host_eligibility WHERE host_account_id = :id")
        .param("id", host)
        .query()
        .singleRow();
  }

  @Test
  void listener_uses_the_service_consumer_group() {
    assertThat(listeners.getListenerContainer("listing-events").getGroupId())
        .isEqualTo("listing-service");
  }

  @Test
  void phone_and_photo_events_make_a_host_eligible_and_older_events_do_not_undo_it()
      throws Exception {
    UUID host = UUID.randomUUID();
    Instant now = Instant.now();
    send(Topics.IDENTITY_ACCOUNT, new PhoneVerified(host, now));
    send(Topics.IDENTITY_ACCOUNT, new PhoneUnverified(host, now.minusSeconds(60))); // stale
    send(Topics.PROFILE, new ProfileUpdated(host, "Asha", "https://cdn/p", true, now));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> {
          Map<String, Object> row = eligibility(host);
          assertThat(row.get("phone_verified")).isEqualTo(true);
          assertThat(row.get("has_profile_photo")).isEqualTo(true);
        });
    Thread.sleep(500);
    assertThat(eligibility(host).get("phone_verified")).isEqualTo(true);

    send(Topics.IDENTITY_ACCOUNT, new PhoneUnverified(host, now.plusSeconds(60)));
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> Boolean.FALSE.equals(eligibility(host).get("phone_verified")));
  }

  @Test
  void suspension_pauses_the_hosts_published_listings() throws Exception {
    User host = host();
    UUID id = published(host);
    send(Topics.IDENTITY_ACCOUNT, new AccountSuspended(host.id(), "fraud", Instant.now()));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            get("/api/v1/listings/" + id, host).andExpect(jsonPath("$.status").value("PAUSED")));
    assertThat(events("ListingPaused", "listingId", id)).hasSize(1);
    post("/api/v1/listings/" + id + "/resume", host)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://parksmart.dev/problems/host-suspended"));
  }

  @Test
  void bookings_block_archiving_until_they_end() throws Exception {
    User host = host();
    UUID listing = published(host);
    UUID booking = UUID.randomUUID();
    Instant start = Instant.now().plus(Duration.ofDays(1));
    send(
        Topics.BOOKING,
        new BookingConfirmed(
            booking,
            listing,
            UUID.randomUUID(),
            host.id(),
            start,
            start.plusSeconds(7200),
            start.plusSeconds(8100),
            new BigDecimal("120.00"),
            "INR",
            Instant.now()));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> delete("/api/v1/listings/" + listing, host).andExpect(status().isConflict()));

    send(
        Topics.BOOKING,
        new BookingCancelled(
            booking,
            listing,
            UUID.randomUUID(),
            host.id(),
            "DRIVER",
            "plans changed",
            new BigDecimal("120.00"),
            "INR",
            start,
            start.plusSeconds(7200),
            Instant.now()));
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> delete("/api/v1/listings/" + listing, host).andExpect(status().isNoContent()));

    // A late duplicate "confirmed" does not revive the ended booking.
    send(
        Topics.BOOKING,
        new BookingConfirmed(
            booking,
            listing,
            UUID.randomUUID(),
            host.id(),
            start,
            start.plusSeconds(7200),
            start.plusSeconds(8100),
            new BigDecimal("120.00"),
            "INR",
            Instant.now()));
    Thread.sleep(1000);
    Object ended =
        jdbc.sql("SELECT ended_at FROM listing_active_bookings WHERE booking_id = :b")
            .param("b", booking)
            .query()
            .singleValue();
    assertThat(ended).isNotNull();
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

  @Test
  void an_end_event_that_arrives_first_still_wins() throws Exception {
    User host = host();
    UUID listing = published(host);
    UUID booking = UUID.randomUUID();
    Instant start = Instant.now().plus(Duration.ofDays(1));
    send(
        Topics.BOOKING,
        new com.parksmart.common.events.booking.BookingExpired(
            booking, listing, UUID.randomUUID(), host.id(), "PENDING_PAYMENT", Instant.now()));
    send(
        Topics.BOOKING,
        new com.parksmart.common.events.booking.BookingRequested(
            booking,
            listing,
            UUID.randomUUID(),
            host.id(),
            start,
            start.plusSeconds(3600),
            new BigDecimal("60.00"),
            "INR",
            true,
            Instant.now().plusSeconds(600),
            Instant.now()));
    // The tombstone is written, and the later "requested" event does not revive it.
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() ->
            jdbc.sql(
                        "SELECT count(*) FROM listing_active_bookings WHERE booking_id = :b"
                            + " AND ended_at IS NOT NULL")
                    .param("b", booking)
                    .query(Long.class)
                    .single()
                == 1);
    Thread.sleep(1000);
    delete("/api/v1/listings/" + listing, host).andExpect(status().isNoContent());
  }

  @Test
  void unparseable_records_go_to_the_dead_letter_topic() throws Exception {
    String key = "poison-" + UUID.randomUUID();
    ProducerRecord<String, String> poison = new ProducerRecord<>(Topics.BOOKING, key, "{not json");
    poison.headers().add("eventType", "BookingConfirmed".getBytes(StandardCharsets.UTF_8));
    PRODUCER.send(poison).get();
    Properties props = new Properties();
    props.putAll(
        Map.of(
            org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG,
            "dlt-test-" + UUID.randomUUID(),
            org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            org.apache.kafka.common.serialization.StringDeserializer.class.getName(),
            org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            org.apache.kafka.common.serialization.StringDeserializer.class.getName()));
    try (var consumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(props)) {
      consumer.subscribe(java.util.List.of(Topics.deadLetterTopic(Topics.BOOKING)));
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
}
