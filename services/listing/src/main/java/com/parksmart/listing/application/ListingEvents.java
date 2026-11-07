package com.parksmart.listing.application;

import com.parksmart.common.events.listing.AvailabilityChanged;
import com.parksmart.common.events.listing.AvailabilityRuleData;
import com.parksmart.common.events.listing.BlackoutData;
import com.parksmart.common.events.listing.ListingArchived;
import com.parksmart.common.events.listing.ListingCreated;
import com.parksmart.common.events.listing.ListingPaused;
import com.parksmart.common.events.listing.ListingPublished;
import com.parksmart.common.events.listing.ListingRejected;
import com.parksmart.common.events.listing.ListingSnapshot;
import com.parksmart.common.events.listing.ListingSubmittedForReview;
import com.parksmart.common.events.listing.ListingUpdated;
import com.parksmart.listing.application.port.EventOutbox;
import com.parksmart.listing.domain.Listing;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns listing changes into events. Search only ever learns about PUBLISHED/PAUSED listings
 * (drafts emit nothing to it); a listing leaving PUBLISHED for review emits {@code
 * ListingSubmittedForReview}, which Search treats as "hide".
 */
@Component
public class ListingEvents {

  private final EventOutbox outbox;

  public ListingEvents(EventOutbox outbox) {
    this.outbox = outbox;
  }

  void created(Listing l) {
    outbox.publish(new ListingCreated(l.id(), l.hostAccountId(), l.createdAt()));
  }

  void submitted(Listing l) {
    outbox.publish(new ListingSubmittedForReview(l.id(), l.hostAccountId(), l.updatedAt()));
  }

  void published(Listing l) {
    outbox.publish(new ListingPublished(snapshot(l), l.updatedAt()));
  }

  /** Public fields of a live listing changed (terms or photos). */
  void updatedIfLive(Listing l) {
    if (l.isLive()) {
      outbox.publish(new ListingUpdated(snapshot(l), l.updatedAt()));
    }
  }

  void availabilityChangedIfLive(Listing l) {
    if (l.isLive()) {
      outbox.publish(new AvailabilityChanged(l.id(), rules(l), blackouts(l), l.updatedAt()));
    }
  }

  void paused(Listing l) {
    outbox.publish(new ListingPaused(l.id(), l.hostAccountId(), l.updatedAt()));
  }

  void rejected(Listing l, String reason) {
    outbox.publish(new ListingRejected(l.id(), l.hostAccountId(), reason, l.updatedAt()));
  }

  void archived(Listing l, Instant at) {
    outbox.publish(new ListingArchived(l.id(), l.hostAccountId(), at));
  }

  static ListingSnapshot snapshot(Listing l) {
    return new ListingSnapshot(
        l.id(),
        l.hostAccountId(),
        l.status().name(),
        l.title(),
        l.address(),
        l.location().lat(),
        l.location().lng(),
        l.timeZone().getId(),
        l.pricePerHour(),
        l.currency(),
        l.maxVehicleType().name(),
        l.amenities().stream().map(Enum::name).sorted().toList(),
        l.instantBook(),
        l.cancellationPolicy().name(),
        l.bufferMinutes(),
        l.thumbnailUrl().orElse(null),
        rules(l),
        blackouts(l));
  }

  private static List<AvailabilityRuleData> rules(Listing l) {
    return l.rules().stream()
        .map(r -> new AvailabilityRuleData(r.day().name(), r.startTime(), r.endTime()))
        .toList();
  }

  private static List<BlackoutData> blackouts(Listing l) {
    return l.blackouts().stream().map(b -> new BlackoutData(b.id(), b.start(), b.end())).toList();
  }
}
