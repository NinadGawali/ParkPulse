package com.parksmart.search.application;

import com.parksmart.search.application.port.ListingSearchIndex;
import com.parksmart.search.application.port.ListingSearchIndex.Snapshot;
import com.parksmart.search.application.port.ListingSearchIndex.Window;
import com.parksmart.search.application.port.ProcessedEvents;
import com.parksmart.search.domain.Availability.Rule;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies consumed events to the index, once each (processed_events), newest wins. */
@Service
public class Projection {

  static final String CONSUMER = "search-service";

  private final ListingSearchIndex index;
  private final ProcessedEvents processed;
  private final Clock clock;

  public Projection(ListingSearchIndex index, ProcessedEvents processed, Clock clock) {
    this.index = index;
    this.processed = processed;
    this.clock = clock;
  }

  @Transactional
  public void snapshot(UUID eventId, Snapshot snapshot, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      index.applySnapshot(snapshot, at, clock.instant());
    }
  }

  @Transactional
  public void availability(
      UUID eventId, UUID listingId, List<Rule> rules, List<Window> blackouts, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      index.applyAvailability(listingId, rules, blackouts, at, clock.instant());
    }
  }

  @Transactional
  public void hide(UUID eventId, UUID listingId, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      index.hide(listingId, at, clock.instant());
    }
  }

  @Transactional
  public void rating(UUID eventId, UUID listingId, BigDecimal avg, int count, Instant at) {
    boolean valid =
        count >= 0
            && (avg == null
                || (avg.compareTo(BigDecimal.ONE) >= 0
                    && avg.compareTo(BigDecimal.valueOf(5)) <= 0));
    if (valid && processed.markProcessed(eventId, CONSUMER)) {
      index.rating(listingId, count == 0 ? null : avg, count, at, clock.instant());
    }
  }

  @Transactional
  public void busy(
      UUID eventId, UUID bookingId, UUID listingId, Instant start, Instant blockedUntil) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      index.busy(bookingId, listingId, start, blockedUntil, clock.instant());
    }
  }

  @Transactional
  public void free(UUID eventId, UUID bookingId) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      index.free(bookingId, clock.instant());
    }
  }
}
