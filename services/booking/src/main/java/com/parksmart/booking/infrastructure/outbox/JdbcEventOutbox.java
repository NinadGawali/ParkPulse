package com.parksmart.booking.infrastructure.outbox;

import com.parksmart.booking.application.port.EventOutbox;
import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.web.CorrelationId;
import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes events to {@code outbox_events} in the caller's transaction. */
@Component
class JdbcEventOutbox implements EventOutbox {

  static final String PRODUCER = "booking-service";

  private final JdbcClient jdbc;
  private final Clock clock;

  JdbcEventOutbox(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(EventPayload payload) {
    EventEnvelope<EventPayload> envelope =
        EventEnvelope.wrap(payload, CorrelationId.currentOrNew(), PRODUCER, clock);
    jdbc.sql(
            """
            INSERT INTO outbox_events (id, topic, partition_key, event_type, payload)
            VALUES (:id, :topic, :key, :type, CAST(:payload AS jsonb))
            """)
        .param("id", envelope.eventId())
        .param("topic", envelope.topic())
        .param("key", envelope.partitionKey())
        .param("type", envelope.eventType())
        .param("payload", EventJson.serialize(envelope))
        .update();
  }
}
