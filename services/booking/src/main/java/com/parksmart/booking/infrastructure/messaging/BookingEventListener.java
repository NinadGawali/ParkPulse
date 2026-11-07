package com.parksmart.booking.infrastructure.messaging;

import com.parksmart.booking.application.BookingSaga;
import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.listing.ListingArchived;
import com.parksmart.common.events.payment.PaymentAuthorized;
import com.parksmart.common.events.payment.PaymentCaptureFailed;
import com.parksmart.common.events.payment.PaymentFailed;
import com.parksmart.common.web.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** The saga inputs: Payment results and withdrawn listings. Other event types are skipped. */
@Component
class BookingEventListener {

  private static final Set<String> HANDLED =
      Set.of("PaymentAuthorized", "PaymentFailed", "PaymentCaptureFailed", "ListingArchived");

  private final BookingSaga saga;

  BookingEventListener(BookingSaga saga) {
    this.saga = saga;
  }

  @KafkaListener(
      id = "booking-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id (booking-service)
      topics = {Topics.PAYMENT, Topics.LISTING},
      autoStartup = "${parksmart.kafka.consumer.enabled:true}")
  void onMessage(ConsumerRecord<String, String> record) {
    String type = header(record, "eventType");
    if (type != null && !HANDLED.contains(type)) {
      return;
    }
    EventEnvelope<EventPayload> envelope = EventJson.deserialize(record.value());
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitize(envelope.correlationId()));
    try {
      var id = envelope.eventId();
      switch (envelope.payload()) {
        case PaymentAuthorized e ->
            saga.paymentAuthorized(
                id,
                e.bookingId(),
                e.driverAccountId(),
                e.hostAccountId(),
                e.amount(),
                e.currency());
        case PaymentFailed e ->
            saga.paymentFailed(
                id, e.bookingId(), e.driverAccountId(), e.hostAccountId(), e.failureCode());
        case PaymentCaptureFailed e ->
            saga.captureFailed(
                id, e.bookingId(), e.driverAccountId(), e.hostAccountId(), e.failureCode());
        case ListingArchived e -> saga.listingArchived(id, e.listingId());
        default -> {
          // Not ours (record had no eventType header): ignore.
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
