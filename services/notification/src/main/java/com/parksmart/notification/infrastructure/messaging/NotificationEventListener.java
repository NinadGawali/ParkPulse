package com.parksmart.notification.infrastructure.messaging;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.web.CorrelationId;
import com.parksmart.notification.application.IncomingEvents;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The only consumer of identity.private.v1 (codes, contact details). Payloads are never logged.
 * Event types without a notice are skipped by header.
 */
@Component
class NotificationEventListener {

  private static final Set<String> HANDLED =
      Set.of(
          "VerificationCodeIssued",
          "ContactDetailsChanged",
          "RoleGranted",
          "AccountSuspended",
          "NotificationPreferencesChanged",
          "BookingAwaitingHostApproval",
          "BookingConfirmed",
          "BookingDeclined",
          "BookingCancelled",
          "BookingExpired",
          "BookingCompleted",
          "BookingNoShow",
          "PaymentCaptureFailed",
          "PaymentRefunded",
          "PayoutCompleted",
          "PayoutFailed",
          "ListingSubmittedForReview",
          "ListingPublished",
          "ListingRejected",
          "ReviewSubmitted");

  private final IncomingEvents events;

  NotificationEventListener(IncomingEvents events) {
    this.events = events;
  }

  @KafkaListener(
      id = "notification-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id (notification-service)
      topics = {
        Topics.IDENTITY_PRIVATE, Topics.IDENTITY_ACCOUNT, Topics.PROFILE, Topics.BOOKING,
        Topics.PAYMENT, Topics.PAYOUT, Topics.LISTING, Topics.REVIEW
      },
      autoStartup = "${parksmart.kafka.consumer.enabled:true}")
  void onMessage(ConsumerRecord<String, String> record) {
    String type = header(record, "eventType");
    if (type != null && !HANDLED.contains(type)) {
      return;
    }
    EventEnvelope<EventPayload> envelope = EventJson.deserialize(record.value());
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitize(envelope.correlationId()));
    try {
      events.handle(envelope.eventId(), envelope.payload());
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
