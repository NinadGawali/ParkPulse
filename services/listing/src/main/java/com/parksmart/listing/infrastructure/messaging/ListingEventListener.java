package com.parksmart.listing.infrastructure.messaging;

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
import com.parksmart.common.events.identity.AccountSuspended;
import com.parksmart.common.events.identity.PhoneUnverified;
import com.parksmart.common.events.identity.PhoneVerified;
import com.parksmart.common.events.payment.PaymentCaptureFailed;
import com.parksmart.common.events.profile.ProfileUpdated;
import com.parksmart.common.web.CorrelationId;
import com.parksmart.listing.application.IncomingEvents;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes Identity, Profile, Booking and Payment events for the host-eligibility and
 * active-booking projections. Event types Listing does not handle are skipped by header without
 * parsing, so new event types never land in the DLT.
 */
@Component
class ListingEventListener {

  private static final Set<String> HANDLED =
      Set.of(
          "PhoneVerified",
          "PhoneUnverified",
          "AccountSuspended",
          "ProfileUpdated",
          "BookingRequested",
          "BookingAwaitingHostApproval",
          "BookingConfirmed",
          "BookingCheckedIn",
          "BookingDeclined",
          "BookingCancelled",
          "BookingExpired",
          "BookingCompleted",
          "BookingNoShow",
          "PaymentCaptureFailed");

  private final IncomingEvents events;

  ListingEventListener(IncomingEvents events) {
    this.events = events;
  }

  @KafkaListener(
      id = "listing-events",
      idIsGroup = false, // group is spring.kafka.consumer.group-id (listing-service)
      topics = {Topics.IDENTITY_ACCOUNT, Topics.PROFILE, Topics.BOOKING, Topics.PAYMENT},
      autoStartup = "${parksmart.kafka.consumer.enabled:true}")
  void onMessage(ConsumerRecord<String, String> record) {
    String type = header(record, "eventType");
    if (type != null && !HANDLED.contains(type)) {
      return;
    }
    EventEnvelope<EventPayload> envelope = EventJson.deserialize(record.value());
    MDC.put(CorrelationId.MDC_KEY, CorrelationId.sanitize(envelope.correlationId()));
    try {
      dispatch(envelope);
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  private void dispatch(EventEnvelope<EventPayload> envelope) {
    var id = envelope.eventId();
    switch (envelope.payload()) {
      case PhoneVerified e -> events.phoneVerified(id, e.accountId(), true, e.verifiedAt());
      case PhoneUnverified e -> events.phoneVerified(id, e.accountId(), false, e.unverifiedAt());
      case AccountSuspended e -> events.accountSuspended(id, e.accountId(), e.suspendedAt());
      case ProfileUpdated e -> events.profilePhoto(id, e.accountId(), e.hasPhoto(), e.updatedAt());
      case BookingRequested e ->
          events.bookingActive(id, e.bookingId(), e.listingId(), e.endTime(), e.requestedAt());
      case BookingAwaitingHostApproval e ->
          events.bookingActive(id, e.bookingId(), e.listingId(), e.endTime(), e.occurredAt());
      case BookingConfirmed e ->
          events.bookingActive(id, e.bookingId(), e.listingId(), e.blockedUntil(), e.confirmedAt());
      case BookingCheckedIn e ->
          events.bookingActive(id, e.bookingId(), e.listingId(), null, e.checkedInAt());
      case BookingDeclined e -> events.bookingEnded(id, e.bookingId(), e.declinedAt());
      case BookingCancelled e -> events.bookingEnded(id, e.bookingId(), e.cancelledAt());
      case BookingExpired e -> events.bookingEnded(id, e.bookingId(), e.expiredAt());
      case BookingCompleted e -> events.bookingEnded(id, e.bookingId(), e.completedAt());
      case BookingNoShow e -> events.bookingEnded(id, e.bookingId(), e.markedAt());
      case PaymentCaptureFailed e -> events.bookingEnded(id, e.bookingId(), e.failedAt());
      default -> {
        // Not ours (record had no eventType header): ignore.
      }
    }
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    Header header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
