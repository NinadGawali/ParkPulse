package com.parksmart.profile.infrastructure.messaging;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.identity.AccountRegistered;
import com.parksmart.common.events.review.RatingAggregateUpdated;
import com.parksmart.common.web.CorrelationId;
import com.parksmart.profile.application.IncomingEvents;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes Identity and Review events. Event types Profile does not handle are skipped by header
 * without parsing, so new event types never land in the DLT.
 */
@Component
class ProfileEventListener {

  private static final Set<String> HANDLED = Set.of("AccountRegistered", "RatingAggregateUpdated");

  private final IncomingEvents events;

  ProfileEventListener(IncomingEvents events) {
    this.events = events;
  }

  @KafkaListener(
      id = "profile-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id (profile-service)
      topics = {Topics.IDENTITY_ACCOUNT, Topics.REVIEW},
      autoStartup = "${parksmart.kafka.consumer.enabled:true}")
  void onMessage(ConsumerRecord<String, String> record) {
    String type = header(record, "eventType");
    if (type != null && !HANDLED.contains(type)) {
      return;
    }
    EventEnvelope<EventPayload> envelope = EventJson.deserialize(record.value());
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitize(envelope.correlationId()));
    try {
      switch (envelope.payload()) {
        case AccountRegistered e -> events.accountRegistered(envelope.eventId(), e);
        case RatingAggregateUpdated e -> events.ratingAggregateUpdated(envelope.eventId(), e);
        default -> {
          // Not ours (no eventType header on the record): ignore.
        }
      }
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
