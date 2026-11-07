package com.parksmart.notification.infrastructure.persistence;

import com.parksmart.notification.application.port.ProcessedEvents;
import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs in the JPA transaction (same connection), so the marker commits with the state change. */
@Repository
class JdbcProcessedEvents implements ProcessedEvents {

  /** Longer than any Kafka retention, so a redelivered event is always recognized. */
  private static final Duration KEEP = Duration.ofDays(30);

  private final JdbcClient jdbc;

  JdbcProcessedEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean markProcessed(UUID eventId, String consumer) {
    return jdbc.sql(
                """
                INSERT INTO processed_events (event_id, consumer) VALUES (:id, :consumer)
                ON CONFLICT DO NOTHING
                """)
            .param("id", eventId)
            .param("consumer", consumer)
            .update()
        == 1;
  }

  @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT5M")
  @Transactional
  public void purgeOld() {
    jdbc.sql("DELETE FROM processed_events WHERE processed_at < now() - CAST(:age AS interval)")
        .param("age", KEEP.toSeconds() + " seconds")
        .update();
  }
}
