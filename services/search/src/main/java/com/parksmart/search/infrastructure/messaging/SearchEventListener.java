package com.parksmart.search.infrastructure.messaging;

import com.parksmart.common.events.EventEnvelope;
import com.parksmart.common.events.EventJson;
import com.parksmart.common.events.EventPayload;
import com.parksmart.common.events.Topics;
import com.parksmart.common.events.booking.BookingAwaitingHostApproval;
import com.parksmart.common.events.booking.BookingCancelled;
import com.parksmart.common.events.booking.BookingCheckedIn;
import com.parksmart.common.events.booking.BookingCompleted;
import com.parksmart.common.events.booking.BookingConfirmed;
import com.parksmart.common.events.booking.BookingDeclined;
import com.parksmart.common.events.booking.BookingExpired;
import com.parksmart.common.events.booking.BookingNoShow;
import com.parksmart.common.events.booking.BookingRequested;
import com.parksmart.common.events.listing.AvailabilityChanged;
import com.parksmart.common.events.listing.AvailabilityRuleData;
import com.parksmart.common.events.listing.BlackoutData;
import com.parksmart.common.events.listing.ListingArchived;
import com.parksmart.common.events.listing.ListingPaused;
import com.parksmart.common.events.listing.ListingPublished;
import com.parksmart.common.events.listing.ListingRejected;
import com.parksmart.common.events.listing.ListingSnapshot;
import com.parksmart.common.events.listing.ListingSubmittedForReview;
import com.parksmart.common.events.listing.ListingUpdated;
import com.parksmart.common.events.payment.PaymentCaptureFailed;
import com.parksmart.common.events.review.RatingAggregateUpdated;
import com.parksmart.common.web.CorrelationId;
import com.parksmart.search.application.Projection;
import com.parksmart.search.application.port.ListingSearchIndex.Snapshot;
import com.parksmart.search.application.port.ListingSearchIndex.Window;
import com.parksmart.search.domain.Availability.Rule;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Builds the index from Listing, Booking, Payment and Review events. */
@Component
class SearchEventListener {

  private static final Set<String> HANDLED =
      Set.of(
          "ListingPublished",
          "ListingUpdated",
          "AvailabilityChanged",
          "ListingPaused",
          "ListingArchived",
          "ListingRejected",
          "ListingSubmittedForReview",
          "BookingRequested",
          "BookingAwaitingHostApproval",
          "BookingConfirmed",
          "BookingCheckedIn",
          "BookingDeclined",
          "BookingCancelled",
          "BookingExpired",
          "BookingCompleted",
          "BookingNoShow",
          "PaymentCaptureFailed",
          "RatingAggregateUpdated");

  private final Projection projection;

  SearchEventListener(Projection projection) {
    this.projection = projection;
  }

  @KafkaListener(
      id = "search-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id (search-service)
      topics = {Topics.LISTING, Topics.BOOKING, Topics.PAYMENT, Topics.REVIEW},
      autoStartup = "${parksmart.kafka.consumer.enabled:true}")
  void onMessage(ConsumerRecord<String, String> record) {
    String type = header(record, "eventType");
    if (type != null && !HANDLED.contains(type)) {
      return;
    }
    EventEnvelope<EventPayload> envelope = EventJson.deserialize(record.value());
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitize(envelope.correlationId()));
    try {
      dispatch(envelope.eventId(), envelope.payload());
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  private void dispatch(UUID id, EventPayload payload) {
    switch (payload) {
      case ListingPublished e -> projection.snapshot(id, snapshot(e.listing()), e.publishedAt());
      case ListingUpdated e -> projection.snapshot(id, snapshot(e.listing()), e.updatedAt());
      case AvailabilityChanged e ->
          projection.availability(
              id,
              e.listingId(),
              rules(e.availabilityRules()),
              windows(e.blackouts()),
              e.changedAt());
      case ListingPaused e -> projection.hide(id, e.listingId(), e.pausedAt());
      case ListingArchived e -> projection.hide(id, e.listingId(), e.archivedAt());
      case ListingRejected e -> projection.hide(id, e.listingId(), e.rejectedAt());
      case ListingSubmittedForReview e -> projection.hide(id, e.listingId(), e.submittedAt());
      case BookingRequested e ->
          projection.busy(id, e.bookingId(), e.listingId(), e.startTime(), e.endTime());
      case BookingAwaitingHostApproval e ->
          projection.busy(id, e.bookingId(), e.listingId(), e.startTime(), e.endTime());
      case BookingConfirmed e ->
          projection.busy(id, e.bookingId(), e.listingId(), e.startTime(), e.blockedUntil());
      case BookingCheckedIn e -> projection.busy(id, e.bookingId(), e.listingId(), null, null);
      case BookingDeclined e -> projection.free(id, e.bookingId());
      case BookingCancelled e -> projection.free(id, e.bookingId());
      case BookingExpired e -> projection.free(id, e.bookingId());
      case BookingCompleted e -> projection.free(id, e.bookingId());
      case BookingNoShow e -> projection.free(id, e.bookingId());
      case PaymentCaptureFailed e -> projection.free(id, e.bookingId());
      case RatingAggregateUpdated e -> {
        if ("LISTING".equals(e.targetType())) {
          projection.rating(id, e.targetId(), e.avgRating(), e.reviewCount(), e.updatedAt());
        }
      }
      default -> {
        // Not ours (record had no eventType header): ignore.
      }
    }
  }

  private static Snapshot snapshot(ListingSnapshot s) {
    return new Snapshot(
        s.listingId(),
        s.hostAccountId(),
        s.status(),
        s.title(),
        s.address(),
        s.latitude(),
        s.longitude(),
        s.timeZone(),
        s.pricePerHour(),
        s.currency(),
        s.maxVehicleType(),
        s.amenities(),
        s.instantBook(),
        s.cancellationPolicy(),
        s.bufferMinutes(),
        s.thumbnailUrl(),
        rules(s.availabilityRules()),
        windows(s.blackouts()));
  }

  private static List<Rule> rules(List<AvailabilityRuleData> data) {
    return data.stream()
        .map(r ->
            new Rule(
                DayOfWeek.valueOf(r.dayOfWeek()), minutes(r.startTime()), minutes(r.endTime())))
        .toList();
  }

  private static int minutes(String hhmm) {
    String[] p = hhmm.split(":");
    return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
  }

  private static List<Window> windows(List<BlackoutData> data) {
    return data.stream().map(b -> new Window(b.start(), b.end())).toList();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
