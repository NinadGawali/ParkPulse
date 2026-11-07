package com.parksmart.listing.application;

import com.parksmart.common.web.ApiException;
import com.parksmart.listing.application.port.ListingRepository;
import com.parksmart.listing.domain.AvailabilityRule;
import com.parksmart.listing.domain.Enums.UnbookableReason;
import com.parksmart.listing.domain.Listing;
import com.parksmart.listing.domain.Values.Blackout;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Weekly rules, blackouts, and the booking quote Booking asks for. */
@Service
public class AvailabilityService {

  private final ListingService listingService;
  private final ListingRepository listings;
  private final ListingEvents events;
  private final Clock clock;

  public AvailabilityService(
      ListingService listingService,
      ListingRepository listings,
      ListingEvents events,
      Clock clock) {
    this.listingService = listingService;
    this.listings = listings;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public ListingService.Versioned replaceRules(
      UUID callerId, UUID listingId, List<AvailabilityRule> rules) {
    Listing listing = listingService.owned(callerId, listingId);
    listing.replaceRules(rules, clock.instant());
    long version = listings.save(listing);
    events.availabilityChangedIfLive(listing);
    return new ListingService.Versioned(listing, version);
  }

  @Transactional
  public Blackout addBlackout(
      UUID callerId, UUID listingId, Instant start, Instant end, String reason) {
    Listing listing = listingService.owned(callerId, listingId);
    Blackout blackout = new Blackout(UUID.randomUUID(), start, end, reason);
    listing.addBlackout(blackout, clock.instant());
    listings.save(listing);
    events.availabilityChangedIfLive(listing);
    return blackout;
  }

  @Transactional
  public void removeBlackout(UUID callerId, UUID listingId, UUID blackoutId) {
    Listing listing = listingService.owned(callerId, listingId);
    listing.removeBlackout(blackoutId, clock.instant());
    listings.save(listing);
    events.availabilityChangedIfLive(listing);
  }

  /** The terms Booking snapshots, and whether the window can be booked. */
  public record Quote(Listing listing, Optional<UnbookableReason> unbookableReason) {
    public boolean bookable() {
      return unbookableReason.isEmpty();
    }
  }

  /**
   * PUBLISHED listings are quoted; PAUSED ones come back unbookable (NOT_PUBLISHED). Listings that
   * were never public, are in review, or are archived are 404, so a forwarded driver token cannot
   * read unpublished terms.
   */
  @Transactional(readOnly = true)
  public Quote quote(UUID listingId, Instant start, Instant end) {
    Listing listing =
        listings
            .findById(listingId)
            .filter(Listing::isLive)
            .orElseThrow(() -> ApiException.notFound("Listing"));
    return new Quote(listing, listing.bookability(start, end));
  }
}
