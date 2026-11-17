package com.parksmart.listing.application;

import com.parksmart.common.security.AuthenticatedAccount;
import com.parksmart.common.security.Role;
import com.parksmart.common.web.ApiException;
import com.parksmart.common.web.Cursor;
import com.parksmart.common.web.CursorPage;
import com.parksmart.listing.application.port.ListingRepository;
import com.parksmart.listing.application.port.Projections.ActiveBookingsProjection;
import com.parksmart.listing.application.port.Projections.HostEligibilityProjection;
import com.parksmart.listing.application.port.Projections.ModerationLog;
import com.parksmart.listing.domain.Enums.ListingStatus;
import com.parksmart.listing.domain.Enums.ModerationDecision;
import com.parksmart.listing.domain.Listing;
import com.parksmart.listing.domain.ListingTerms;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listing lifecycle. Every mutation checks ownership here (403 for anyone but the host); the
 * controller checks roles. Concurrent edits are caught by the listing's version (409/412).
 */
@Service
public class ListingService {

  private final ListingRepository listings;
  private final HostEligibilityProjection eligibility;
  private final ActiveBookingsProjection activeBookings;
  private final ModerationLog moderation;
  private final ListingEvents events;
  private final ListingProperties properties;
  private final Clock clock;

  public ListingService(
      ListingRepository listings,
      HostEligibilityProjection eligibility,
      ActiveBookingsProjection activeBookings,
      ModerationLog moderation,
      ListingEvents events,
      ListingProperties properties,
      Clock clock) {
    this.listings = listings;
    this.eligibility = eligibility;
    this.activeBookings = activeBookings;
    this.moderation = moderation;
    this.events = events;
    this.properties = properties;
    this.clock = clock;
  }

  /** A listing with its current version (the ETag). */
  public record Versioned(Listing listing, long version) {}

  /** What a caller may see of a listing. */
  public record ListingView(
      Listing listing, long version, boolean privileged, String rejectionReason) {}

  @Transactional
  public Versioned create(UUID hostAccountId, ListingTerms terms) {
    Listing listing =
        Listing.create(
            UUID.randomUUID(), hostAccountId, terms, properties.priceBounds(), clock.instant());
    long version = listings.save(listing);
    events.created(listing);
    return new Versioned(listing, version);
  }

  /** PUBLISHED is public; anything else only for the owner or an admin (404 otherwise). */
  @Transactional(readOnly = true)
  public ListingView view(Optional<AuthenticatedAccount> caller, UUID listingId) {
    Listing listing = listings.findById(listingId).orElseThrow(ListingService::notFound);
    boolean privileged = caller.map(c -> c.ownsOrAdmin(listing.hostAccountId())).orElse(false);
    if (!listing.isPublic() && !privileged) {
      throw notFound();
    }
    String reason =
        privileged && listing.status() == ListingStatus.REJECTED
            ? moderation.latestRejectionReason(listingId).orElse(null)
            : null;
    return new ListingView(listing, listing.version(), privileged, reason);
  }

  /** Partial update guarded by If-Match. */
  @Transactional
  public Versioned update(UUID callerId, UUID listingId, long expectedVersion, ListingTerms patch) {
    Listing listing = owned(callerId, listingId);
    if (listing.version() != expectedVersion) {
      throw new ApiException(
          HttpStatus.PRECONDITION_FAILED,
          "precondition-failed",
          "Listing changed",
          "The listing was changed since you loaded it; reload and retry");
    }
    Listing.Change change = listing.update(patch, properties.priceBounds(), clock.instant());
    if (change == Listing.Change.NONE) {
      return new Versioned(listing, listing.version());
    }
    long version = listings.save(listing);
    if (change == Listing.Change.RELOCATED) {
      events.submitted(listing);
    } else {
      events.updatedIfLive(listing);
    }
    return new Versioned(listing, version);
  }

  @Transactional
  public Versioned submit(AuthenticatedAccount caller, UUID listingId) {
    Listing listing = owned(caller.accountId(), listingId);
    listing.submit(
        caller.hasRole(Role.HOST), eligibility.find(listing.hostAccountId()), clock.instant());
    long version = listings.save(listing);
    events.submitted(listing);
    return new Versioned(listing, version);
  }

  @Transactional
  public Versioned pause(UUID callerId, UUID listingId) {
    Listing listing = owned(callerId, listingId);
    listing.pause(clock.instant());
    long version = listings.save(listing);
    events.paused(listing);
    return new Versioned(listing, version);
  }

  @Transactional
  public Versioned resume(UUID callerId, UUID listingId) {
    Listing listing = owned(callerId, listingId);
    listing.resume(
        eligibility.findForPublish(listing.hostAccountId(), clock.instant()), clock.instant());
    long version = listings.save(listing);
    events.published(listing);
    return new Versioned(listing, version);
  }

  /** Soft delete; refused while a booking is active or upcoming. */
  @Transactional
  public void archive(UUID callerId, UUID listingId) {
    Listing listing = owned(callerId, listingId);
    Instant now = clock.instant();
    if (activeBookings.hasBlocking(listingId, now)) {
      throw ApiException.conflict(
          "listing-has-active-bookings",
          "Listing has active bookings",
          "Cancel or finish the active and upcoming bookings before archiving");
    }
    listing.archive(now);
    listings.save(listing);
    events.archived(listing, now);
  }

  /** The host's own listings (any status but ARCHIVED), newest first. */
  @Transactional(readOnly = true)
  public CursorPage<Listing> mine(
      UUID hostAccountId, ListingStatus status, String cursor, Integer limit) {
    Cursor after = Cursor.decode(cursor);
    int size = Cursor.limit(limit);
    List<Listing> rows =
        listings.pageByHost(
            hostAccountId,
            status,
            after == null ? null : after.sortKey(),
            after == null ? null : after.id(),
            size + 1);
    return page(rows, size, Listing::createdAt);
  }

  // ---------------------------------------------------------------- admin

  /** Moderation queue: oldest change first. */
  @Transactional(readOnly = true)
  public CursorPage<Listing> byStatus(ListingStatus status, String cursor, Integer limit) {
    Cursor after = Cursor.decode(cursor);
    int size = Cursor.limit(limit);
    List<Listing> rows =
        listings.pageByStatus(
            status,
            after == null ? null : after.sortKey(),
            after == null ? null : after.id(),
            size + 1);
    return page(rows, size, Listing::updatedAt);
  }

  @Transactional
  public Versioned approve(UUID adminId, UUID listingId, String note) {
    Listing listing = moderatable(adminId, listingId);
    Instant now = clock.instant();
    listing.approve(eligibility.findForPublish(listing.hostAccountId(), now), now);
    long version = listings.save(listing);
    moderation.record(
        listingId, adminId, ModerationDecision.APPROVED.name(), Listing.cleanNote(note), now);
    events.published(listing);
    return new Versioned(listing, version);
  }

  @Transactional
  public Versioned reject(UUID adminId, UUID listingId, String reason) {
    Listing listing = moderatable(adminId, listingId);
    Instant now = clock.instant();
    String clean = listing.reject(reason, now);
    long version = listings.save(listing);
    moderation.record(listingId, adminId, ModerationDecision.REJECTED.name(), clean, now);
    events.rejected(listing, clean);
    return new Versioned(listing, version);
  }

  // ---------------------------------------------------------------- helpers

  /** The listing, if the caller is its host (403 for anyone else, admins included). */
  Listing owned(UUID callerId, UUID listingId) {
    Listing listing = listings.findById(listingId).orElseThrow(ListingService::notFound);
    if (!listing.isOwnedBy(callerId)) {
      throw ApiException.forbidden("Not your listing");
    }
    return listing;
  }

  /** Admins cannot moderate their own listings. */
  private Listing moderatable(UUID adminId, UUID listingId) {
    Listing listing = listings.findById(listingId).orElseThrow(ListingService::notFound);
    if (listing.isOwnedBy(adminId)) {
      throw ApiException.forbidden("Admins cannot moderate their own listings");
    }
    return listing;
  }

  static ApiException notFound() {
    return ApiException.notFound("Listing");
  }

  private static CursorPage<Listing> page(
      List<Listing> rows, int size, java.util.function.Function<Listing, Instant> sortKey) {
    if (rows.size() <= size) {
      return new CursorPage<>(rows, null);
    }
    List<Listing> items = rows.subList(0, size);
    Listing last = items.get(size - 1);
    return new CursorPage<>(items, new Cursor(sortKey.apply(last), last.id()).encode());
  }
}
