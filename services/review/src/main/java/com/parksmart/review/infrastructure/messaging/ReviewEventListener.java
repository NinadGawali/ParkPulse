package com.parksmart.review.infrastructure.messaging;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingCompleted;
import com.parksmart.common.web.CorrelationId;
import com.parksmart.review.application.ReviewService;
import com.parksmart.review.domain.Review.Eligibility;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** BookingCompleted opens review eligibility. Other event types on the topic are skipped. */
@Component
class ReviewEventListener {

  private final ReviewService reviews;

  ReviewEventListener(ReviewService reviews) {
    this.reviews = reviews;
  }

  @KafkaListener(
      id = "review-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id
      topics = Topics.BOOKING,
      autoStartup = "${parksmart.kafka.consumer.enabled:true}")
  void onMessage(ConsumerRecord<String, String> record) {
    Header type = record.headers().lastHeader("eventType");
    if (type != null
        && !"BookingCompleted".equals(new String(type.value(), StandardCharsets.UTF_8))) {
      return;
    }
    EventEnvelope<EventPayload> envelope = EventJson.deserialize(record.value());
    if (!(envelope.payload() instanceof BookingCompleted completed)) {
      return;
    }
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitize(envelope.correlationId()));
    try {
      reviews.bookingCompleted(
          envelope.eventId(),
          new Eligibility(
              completed.bookingId(),
              completed.driverAccountId(),
              completed.hostAccountId(),
              completed.listingId(),
              completed.reviewWindowEndsAt()),
          completed.completedAt());
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }
}
