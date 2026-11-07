package com.parksmart.listing.application;

import com.parksmart.listing.application.port.ListingRepository;
import com.parksmart.listing.application.port.ProcessedEvents;
import com.parksmart.listing.application.port.Projections.ActiveBookingsProjection;
import com.parksmart.listing.application.port.Projections.HostEligibilityProjection;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Listing;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumed events. Every handler records the event in processed_events in the same transaction, and
 * the projections themselves ignore values older than what they hold.
 */
@Service
public class IncomingEvents {

  static final String CONSUMER = "listing-service";

  private final ProcessedEvents processed;
  private final HostEligibilityProjection eligibility;
  private final ActiveBookingsProjection activeBookings;
  private final ListingRepository listings;
  private final ListingEvents events;
  private final Clock clock;

  public IncomingEvents(
      ProcessedEvents processed,
      HostEligibilityProjection eligibility,
      ActiveBookingsProjection activeBookings,
      ListingRepository listings,
      ListingEvents events,
      Clock clock) {
    this.processed = processed;
    this.eligibility = eligibility;
    this.activeBookings = activeBookings;
    this.listings = listings;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public void phoneVerified(UUID eventId, UUID accountId, boolean verified, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      eligibility.phoneVerified(accountId, verified, at);
    }
  }

  @Transactional
  public void profilePhoto(UUID eventId, UUID accountId, boolean hasPhoto, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      eligibility.profilePhoto(accountId, hasPhoto, at);
    }
  }

  /** Marks the host suspended and pauses every PUBLISHED listing (ListingPaused for each). */
  @Transactional
  public void accountSuspended(UUID eventId, UUID accountId, Instant at) {
    if (!processed.markProcessed(eventId, CONSUMER)) {
      return;
    }
    eligibility.suspended(accountId, at);
    Instant now = clock.instant();
    for (Listing listing : listings.findByHostAndStatus(accountId, ListingStatus.PUBLISHED)) {
      if (listing.pauseForSuspension(now)) {
        listings.save(listing);
        events.paused(listing);
      }
    }
  }

  @Transactional
  public void bookingActive(
      UUID eventId, UUID bookingId, UUID listingId, Instant blockedUntil, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      activeBookings.active(bookingId, listingId, blockedUntil, at);
    }
  }

  @Transactional
  public void bookingEnded(UUID eventId, UUID bookingId, Instant at) {
    if (processed.markProcessed(eventId, CONSUMER)) {
      activeBookings.ended(bookingId, at);
    }
  }
}
