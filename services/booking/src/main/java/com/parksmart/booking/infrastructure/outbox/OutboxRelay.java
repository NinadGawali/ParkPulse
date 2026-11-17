package com.parksmart.booking.infrastructure.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox rows to Kafka in creation order (at-least-once). Rows are locked with {@code FOR
 * UPDATE SKIP LOCKED} so several instances can run. On the first send failure the batch stops,
 * keeping per-key order; the remaining rows are retried on the next poll.
 */
@Component
@ConditionalOnProperty(value = "parksmart.outbox.relay.enabled", matchIfMissing = true)
class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int BATCH_SIZE = 100;
  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final JdbcClient jdbc;
  private final KafkaTemplate<String, String> kafka;

  OutboxRelay(JdbcClient jdbc, KafkaTemplate<String, String> kafka) {
    this.jdbc = jdbc;
    this.kafka = kafka;
  }

  @Scheduled(fixedDelayString = "${parksmart.outbox.relay.poll-interval:500ms}")
  @Transactional
  public void publishPending() {
    List<OutboxRow> rows =
        jdbc.sql(
                """
                SELECT id, topic, partition_key, event_type, payload::text AS payload
                FROM outbox_events WHERE published_at IS NULL
                ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED
                """)
            .param("limit", BATCH_SIZE)
            .query((rs, n) ->
                new OutboxRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("topic"),
                    rs.getString("partition_key"),
                    rs.getString("event_type"),
                    rs.getString("payload")))
            .list();

    for (OutboxRow row : rows) {
      try {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(row.topic(), row.partitionKey(), row.payload());
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.id().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        // Never log the payload.
        log.warn(
            "Outbox publish failed for event {} ({}); will retry", row.id(), row.eventType(), e);
        return;
      }
      jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id")
          .param("id", row.id())
          .update();
    }
  }

  /** Published rows are kept a week for debugging, then removed. */
  @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
  @Transactional
  public void purgePublished() {
    jdbc.sql("DELETE FROM outbox_events WHERE published_at < now() - CAST(:age AS interval)")
        .param("age", Duration.ofDays(7).toSeconds() + " seconds")
        .update();
  }

  record OutboxRow(UUID id, String topic, String partitionKey, String eventType, String payload) {}
}
