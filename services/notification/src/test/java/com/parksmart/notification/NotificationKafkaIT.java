package com.parksmart.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.identity.VerificationCodeIssued;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/** Real Kafka: a code on the private topic becomes a pending email; the group is right. */
class NotificationKafkaIT extends IntegrationTest {

  @Autowired KafkaListenerEndpointRegistry registry;

  @Test
  void a_code_on_the_private_topic_becomes_a_pending_email() throws Exception {
    assertThat(registry.getListenerContainer("notification-events").getGroupId())
        .isEqualTo("notification-service");
    UUID account = UUID.randomUUID();
    String to = "kafka-" + account + "@example.com";
    EventEnvelope<EventPayload> e =
        EventEnvelope.wrap(
            new VerificationCodeIssued(
                account,
                "PASSWORD_RESET",
                "EMAIL",
                to,
                "tok-123456",
                Instant.now().plusSeconds(900)),
            "corr-1",
            "identity-service",
            Clock.systemUTC());
    Properties props = new Properties();
    props.putAll(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
      ProducerRecord<String, String> r =
          new ProducerRecord<>(Topics.IDENTITY_PRIVATE, account.toString(), EventJson.serialize(e));
      r.headers().add("eventType", "VerificationCodeIssued".getBytes(StandardCharsets.UTF_8));
      producer.send(r).get();
    }
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() ->
            jdbc.sql("SELECT count(*) FROM notifications WHERE destination = :d AND sensitive")
                    .param("d", to)
                    .query(Long.class)
                    .single()
                == 1);
  }
}
