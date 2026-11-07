package com.parksmart.payment.infrastructure.messaging;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.booking.BookingDeclined;
import com.parksmart.common.events.booking.BookingExpired;
import com.parksmart.common.events.booking.BookingRequested;
import com.parksmart.common.web.CorrelationId;
import com.parksmart.payment.application.PaymentSaga;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Booking events drive the payment saga; other types are skipped by header. */
@Component
class PaymentEventListener {

  private static final Set<String> HANDLED =
      Set.of(
          "BookingRequested",
          "BookingConfirmed",
          "BookingDeclined",
          "BookingExpired",
          "BookingCancelled");

  private final PaymentSaga saga;

  PaymentEventListener(PaymentSaga saga) {
    this.saga = saga;
  }

  @KafkaListener(
      id = "payment-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id (payment-service)
      topics = Topics.BOOKING,
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
        case BookingRequested e ->
            saga.bookingRequested(
                id,
                e.bookingId(),
                e.driverAccountId(),
                e.hostAccountId(),
                e.totalAmount(),
                e.currency(),
                e.paymentDueBy());
        case BookingConfirmed e ->
            saga.bookingConfirmed(
                id, e.bookingId(), e.driverAccountId(), e.hostAccountId(), e.totalAmount());
        case BookingDeclined e ->
            saga.bookingEnded(
                id, e.bookingId(), e.driverAccountId(), e.hostAccountId(), "booking declined");
        case BookingExpired e ->
            saga.bookingEnded(
                id, e.bookingId(), e.driverAccountId(), e.hostAccountId(), "booking expired");
        case BookingCancelled e ->
            saga.bookingCancelled(
                id, e.bookingId(), e.driverAccountId(), e.hostAccountId(), e.refundAmount());
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
